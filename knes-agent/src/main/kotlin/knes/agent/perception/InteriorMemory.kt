package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent per-mapId memory of interior tile observations.
 *
 * Companion to [OverworldMemory] but keyed by (mapId, tileX, tileY) where
 * tileX/Y are FF1's `sm_player_x`/`sm_player_y` (V5.6 canonical party tile,
 * not camera scroll). One file per save-game lineage; default location
 * `~/.knes/ff1-interior-memory.json`.
 *
 * Inspired by Gemini Plays Pokemon's "Map Markers" + "Mental Map" tools
 * (jcz.dev): persistent POIs (DOOR/STAIRS/WARP) and a visited set per map
 * unblock two anti-patterns documented in HANDOFF V5.7:
 *   1. Pathfinder repeatedly tries the same south-edge heuristic in castles
 *      where the real exit is a STAIRS tile; with memory we prefer known
 *      POIs over geometric fallbacks.
 *   2. Vision agent claims STUCK before exploring all reachable tiles. With
 *      a visited set we can compute "unvisited reachable frontier" and
 *      prioritise exploration over exit-seeking until the map is mapped out.
 *
 * Conflict resolution: observations have a priority ordering (VISITED is
 * lowest, EXIT_CONFIRMED is highest). A higher-priority observation
 * REPLACES a lower one; same-priority increments confirmCount; lower
 * priority is ignored.
 */
@Serializable
enum class InteriorObservation {
    /** Party tile stood here at least once. */
    VISITED,

    /**
     * Tile classified as STAIRS (0x44 in FF1 interiors) — SIGHTING ONLY.
     *
     * V5.12 evidence: in FF1 castles, STAIRS triggers a sub-map transition
     * (going up/down floors), NOT an exit to the overworld. Recording the
     * observation is still useful (for `InteriorFrontier` frontier hints and
     * "I've been here" diagnostics) but [InteriorPathfinder] explicitly does
     * NOT target POI_STAIRS as an exit candidate (V5.13). Promotion to a
     * confirmed exit happens only via the per-tile [EXIT_CONFIRMED] record
     * after we observe `mapflags bit 0 → 0` post-step.
     */
    POI_STAIRS,

    /** Tile classified by [InteriorTileClassifier] (or analogue) as DOOR. */
    POI_DOOR,

    /** Tile classified as WARP. */
    POI_WARP,

    /** Stepping onto this tile flipped mapflags bit 0 → 0 (transitioned out). */
    EXIT_CONFIRMED,
}

@Serializable
data class InteriorTileFact(
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val observation: InteriorObservation,
    val confirmCount: Int = 1,
    val note: String = "",
)

@Serializable
private data class InteriorMemoryFile(
    val version: Int = 1,
    val facts: List<InteriorTileFact> = emptyList(),
)

class InteriorMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val byKey: MutableMap<Triple<Int, Int, Int>, InteriorTileFact> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(InteriorMemoryFile.serializer(), file.readText())
            for (f in parsed.facts) byKey[Triple(f.mapId, f.tileX, f.tileY)] = f
        } catch (e: Exception) {
            System.err.println("[interior-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val payload = InteriorMemoryFile(
            facts = byKey.values.sortedWith(
                compareBy({ it.mapId }, { it.tileY }, { it.tileX }),
            ),
        )
        file.writeText(json.encodeToString(InteriorMemoryFile.serializer(), payload))
    }

    fun get(mapId: Int, tileX: Int, tileY: Int): InteriorTileFact? =
        byKey[Triple(mapId, tileX, tileY)]

    /** Set of (tileX, tileY) the party has stood on or confirmed for [mapId]. */
    fun visited(mapId: Int): Set<Pair<Int, Int>> =
        byKey.values.asSequence()
            .filter { it.mapId == mapId }
            .map { it.tileX to it.tileY }
            .toSet()

    /** All POI / EXIT_CONFIRMED facts for [mapId], excluding plain VISITED. */
    fun pois(mapId: Int): List<InteriorTileFact> =
        byKey.values.filter { it.mapId == mapId && it.observation != InteriorObservation.VISITED }

    /** All facts (debug / dump). */
    fun all(): List<InteriorTileFact> = byKey.values.toList()

    /**
     * Record an observation. Priority order is the declaration order of
     * [InteriorObservation]: higher ordinal wins on conflict. Same-priority
     * collisions increment [InteriorTileFact.confirmCount]. Lower-priority
     * observations are silently dropped (returns the existing fact).
     */
    fun record(
        mapId: Int,
        tileX: Int,
        tileY: Int,
        observation: InteriorObservation,
        note: String = "",
    ): InteriorTileFact {
        val key = Triple(mapId, tileX, tileY)
        val existing = byKey[key]
        if (existing != null) {
            if (existing.observation.ordinal > observation.ordinal) return existing
            if (existing.observation == observation) {
                val updated = existing.copy(
                    confirmCount = existing.confirmCount + 1,
                    note = if (note.isNotBlank()) note else existing.note,
                )
                byKey[key] = updated
                return updated
            }
        }
        val fact = InteriorTileFact(mapId, tileX, tileY, observation, confirmCount = 1, note = note)
        byKey[key] = fact
        return fact
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-interior-memory.json")
    }
}
