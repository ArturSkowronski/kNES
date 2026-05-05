package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class LandmarkKind {
    TOWN_ENTRY, CASTLE_ENTRY, DUNGEON_ENTRY,
    NPC_KING, NPC_SHOPKEEPER, NPC_GENERIC,
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
        file.parentFile?.mkdirs()
        val payload = LandmarkFile(landmarks = byId.values.sortedBy { it.id })
        file.writeText(json.encodeToString(LandmarkFile.serializer(), payload))
    }

    fun record(l: Landmark) { byId[l.id] = l }

    /** Records iff no existing landmark matches on (kind + world coords) for overworld
     *  or (kind + mapId + local coords) for interior. Returns true if added. */
    fun recordIfNew(l: Landmark): Boolean {
        val dup = byId.values.firstOrNull { existing ->
            existing.kind == l.kind &&
                existing.worldX == l.worldX && existing.worldY == l.worldY &&
                existing.mapId == l.mapId && existing.localX == l.localX && existing.localY == l.localY
        }
        if (dup != null) return false
        byId[l.id] = l
        return true
    }

    fun findByKind(vararg kinds: LandmarkKind): List<Landmark> =
        byId.values.filter { it.kind in kinds }

    fun atLocation(worldX: Int, worldY: Int): Landmark? =
        byId.values.firstOrNull { it.worldX == worldX && it.worldY == worldY }

    fun markVisited(id: String) {
        byId[id]?.let { byId[id] = it.copy(visited = true) }
    }

    fun all(): List<Landmark> = byId.values.toList()

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-landmarks.json")
    }
}
