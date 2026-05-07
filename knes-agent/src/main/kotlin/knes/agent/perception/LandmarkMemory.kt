package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class LandmarkKind {
    TOWN_ENTRY, CASTLE_ENTRY, DUNGEON_ENTRY, TEMPLE_ENTRY,
    NPC_KING, NPC_SHOPKEEPER, NPC_INNKEEPER, NPC_GENERIC,
    STAIRS_UP, STAIRS_DOWN, EXIT_TILE,
    UNKNOWN,
}

@Serializable
data class Landmark(
    val id: String,
    val kind: LandmarkKind,
    /** For overworld landmarks: world coordinates. Null for interior-only landmarks. */
    val worldX: Int? = null,
    val worldY: Int? = null,
    /** For interior landmarks: which mapId, plus local coords. Null for overworld-only. */
    val mapId: Int? = null,
    val localX: Int? = null,
    val localY: Int? = null,
    /** When the landmark is the entry tile to a known interior. */
    val mapIdInterior: Int? = null,
    val visited: Boolean = false,
    val note: String = "",
    val discoveredRunId: String = "",
)

@Serializable
private data class LandmarkFile(
    val version: Int = 1,
    val landmarks: List<Landmark> = emptyList(),
)

class LandmarkMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val byId: MutableMap<String, Landmark> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(LandmarkFile.serializer(), file.readText())
            for (l in parsed.landmarks) byId[l.id] = l
        } catch (e: Exception) {
            System.err.println("[landmark-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        val payload = LandmarkFile(landmarks = byId.values.sortedBy { it.id })
        AtomicJsonWriter.write(file, json.encodeToString(LandmarkFile.serializer(), payload))
    }

    fun record(l: Landmark) { byId[l.id] = l }

    /** Records iff no existing landmark matches on (kind + world coords) for overworld
     *  or (kind + mapId + local coords) for interior. Returns true if added.
     *
     *  When a duplicate exists, fields on the existing record are upgraded
     *  monotonically with stronger info from the new record: visited flag
     *  becomes true if either is true; mapIdInterior fills in if previously
     *  null; note + discoveredRunId fill in if previously blank. This prevents
     *  the case where SalienceStrategy auto-records a tile-tagged candidate
     *  (visited=false, mapIdInterior=null), then later handleNewInterior
     *  records the confirmed entry (visited=true, mapIdInterior=N) and the
     *  stronger record was discarded. */
    fun recordIfNew(l: Landmark): Boolean {
        val isOverworld = l.worldX != null && l.worldY != null
        val isInterior = l.mapId != null && l.localX != null && l.localY != null

        val dup = byId.values.firstOrNull { existing ->
            if (existing.kind != l.kind) return@firstOrNull false
            when {
                isOverworld && existing.worldX != null && existing.worldY != null ->
                    existing.worldX == l.worldX && existing.worldY == l.worldY
                isInterior && existing.mapId != null && existing.localX != null && existing.localY != null ->
                    existing.mapId == l.mapId &&
                        existing.localX == l.localX && existing.localY == l.localY
                else -> false  // coord-less landmarks are never deduped
            }
        }
        if (dup != null) {
            val upgraded = dup.copy(
                visited = dup.visited || l.visited,
                mapIdInterior = dup.mapIdInterior ?: l.mapIdInterior,
                note = if (dup.note.isBlank() && l.note.isNotBlank()) l.note else dup.note,
                discoveredRunId = if (dup.discoveredRunId.isBlank() && l.discoveredRunId.isNotBlank())
                    l.discoveredRunId else dup.discoveredRunId,
            )
            if (upgraded != dup) byId[dup.id] = upgraded
            return false
        }
        byId[l.id] = l
        return true
    }

    fun findByKind(vararg kinds: LandmarkKind): List<Landmark> =
        byId.values.filter { it.kind in kinds }

    fun atLocation(worldX: Int, worldY: Int): Landmark? =
        byId.values.firstOrNull { it.worldX == worldX && it.worldY == worldY }

    /** Remove all tile-tagged candidates (mapIdInterior == null) at exact world coords.
     *  Confirmed entries (mapIdInterior != null) are preserved.
     *
     *  Used by `SingleRun` to purge salience priority-2 misclassifications when the
     *  pathfinder reports the target as impassable terrain or no-path-within-viewport.
     *  Without this, every run picks the same bogus landmark again and idles out. */
    fun removeTileTaggedAt(worldX: Int, worldY: Int): Boolean {
        val toRemove = byId.values.filter {
            it.worldX == worldX && it.worldY == worldY && it.mapIdInterior == null
        }
        if (toRemove.isEmpty()) return false
        toRemove.forEach { byId.remove(it.id) }
        return true
    }

    fun markVisited(id: String) {
        byId[id]?.let { byId[id] = it.copy(visited = true) }
    }

    fun all(): List<Landmark> = byId.values.toList()

    fun findInnkeeper(): Landmark? = byId.values.firstOrNull { it.kind == LandmarkKind.NPC_INNKEEPER }

    fun findTempleEntry(): Landmark? = byId.values.firstOrNull { it.kind == LandmarkKind.TEMPLE_ENTRY }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-landmarks.json")
    }
}
