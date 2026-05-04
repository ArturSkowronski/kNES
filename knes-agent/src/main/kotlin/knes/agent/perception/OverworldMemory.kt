package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent memory of overworld tile observations across sessions.
 *
 * Built incrementally — like a player exploring: each empirical observation
 * (BFS-target a candidate tile, step on it, observe locType/mapId) is recorded
 * and saved to disk. Future sessions load the file and skip re-probing tiles
 * already classified as DECOR/UNREACHABLE, and walk straight to known ENTRY
 * tiles without rediscovery.
 *
 * Storage: `~/.knes/ff1-ow-memory.json` by default (override via constructor).
 *
 * Pattern: classic "episodic memory" / "frontier exploration" — accumulate,
 * never start from zero.
 */
@Serializable
enum class TileObservation {
    /** Stepping on this tile triggered an interior load (locType != 0). */
    ENTRY,
    /** Walked over (or BFS reached) without locType change — visible tile, not a teleport. */
    DECOR,
    /** BFS could not route here / movement blocked / not reachable from explored area. */
    UNREACHABLE,
}

@Serializable
data class TileFact(
    val worldX: Int,
    val worldY: Int,
    val observation: TileObservation,
    /** mapId observed when entering, if observation == ENTRY. */
    val enteredMapId: Int? = null,
    /** Sessions that confirmed this fact (deduplication helper). */
    val confirmCount: Int = 1,
    /** Free-form note for debugging. */
    val note: String = "",
)

@Serializable
private data class MemoryFile(
    val version: Int = 1,
    val facts: List<TileFact> = emptyList(),
)

class OverworldMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val byCoord: MutableMap<Pair<Int, Int>, TileFact> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(MemoryFile.serializer(), file.readText())
            for (f in parsed.facts) byCoord[f.worldX to f.worldY] = f
        } catch (e: Exception) {
            System.err.println("[ow-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val payload = MemoryFile(facts = byCoord.values.sortedWith(compareBy({ it.worldY }, { it.worldX })))
        file.writeText(json.encodeToString(MemoryFile.serializer(), payload))
    }

    fun get(worldX: Int, worldY: Int): TileFact? = byCoord[worldX to worldY]

    /** Find the first ENTRY tile in the given candidate list (memory-only, no probing). */
    fun knownEntry(candidates: List<Pair<Int, Int>>): TileFact? =
        candidates.firstNotNullOfOrNull { (x, y) ->
            byCoord[x to y]?.takeIf { it.observation == TileObservation.ENTRY }
        }

    /**
     * Record a new observation. If the tile is already recorded with the same observation,
     * confirmCount is incremented; if a conflicting observation arrives the new one
     * REPLACES the old (last-write-wins) and confirmCount resets.
     */
    fun record(
        worldX: Int,
        worldY: Int,
        observation: TileObservation,
        enteredMapId: Int? = null,
        note: String = "",
    ): TileFact {
        val existing = byCoord[worldX to worldY]
        val fact = if (existing != null && existing.observation == observation) {
            existing.copy(
                confirmCount = existing.confirmCount + 1,
                enteredMapId = enteredMapId ?: existing.enteredMapId,
                note = if (note.isNotBlank()) note else existing.note,
            )
        } else {
            TileFact(worldX, worldY, observation, enteredMapId, confirmCount = 1, note = note)
        }
        byCoord[worldX to worldY] = fact
        return fact
    }

    fun all(): List<TileFact> = byCoord.values.toList()

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-ow-memory.json")
    }
}
