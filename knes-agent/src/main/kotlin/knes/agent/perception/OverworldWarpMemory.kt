package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent memory of FF1 overworld tiles that are hidden interior-warp
 * entries the [OverworldTileClassifier] does not model.
 *
 * V5.25 problem: each fresh agent run rediscovers warp tiles by walking into
 * them. Iter1-iter7 all hit (145, 152) (Coneria warp) on turn 4-5, lost most
 * of the budget escaping mapId=24, never reached Garland. Per-session memory
 * (V5.23) only protects from the SECOND visit; the first one always trips.
 *
 * V5.25 fix: persist the set across runs in `~/.knes/ff1-overworld-warps.json`.
 * AgentSession loads the file on startup, seeds [knes.agent.runtime.AgentSession.failedWarpTiles]
 * AND [FogOfWar.markBlocked] on each known tile's 3x3 zone before the agent
 * even moves. After the first run that detects (X, Y), all subsequent runs
 * route around it from turn 1.
 *
 * Companion to [InteriorMemory] (per-mapId interior facts) and
 * [OverworldMemory] (overworld-wide POI memory). Where [InteriorMemory] keeps
 * "I've been here" + POIs inside maps, this file is exclusively about
 * "stepping on this overworld tile triggers an unwanted interior".
 */
@Serializable
private data class OverworldWarpFile(
    val version: Int = 1,
    val tiles: List<WarpTile> = emptyList(),
)

@Serializable
data class WarpTile(
    val worldX: Int,
    val worldY: Int,
    /** Optional FF1 mapId the warp leads into (0 if unknown). */
    val mapId: Int = 0,
    /** Free-form note: "iter1 evidence — Coneria Town outer entry". */
    val note: String = "",
)

class OverworldWarpMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val byKey: MutableMap<Pair<Int, Int>, WarpTile> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(OverworldWarpFile.serializer(), file.readText())
            for (t in parsed.tiles) byKey[t.worldX to t.worldY] = t
        } catch (e: Exception) {
            System.err.println("[overworld-warp-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        val payload = OverworldWarpFile(
            tiles = byKey.values.sortedWith(compareBy({ it.worldY }, { it.worldX })),
        )
        AtomicJsonWriter.write(file, json.encodeToString(OverworldWarpFile.serializer(), payload))
    }

    fun all(): Set<Pair<Int, Int>> = byKey.keys.toSet()

    fun record(worldX: Int, worldY: Int, mapId: Int = 0, note: String = ""): WarpTile {
        val key = worldX to worldY
        val existing = byKey[key]
        // Preserve note/mapId from the more informative record.
        val merged = WarpTile(
            worldX = worldX,
            worldY = worldY,
            mapId = if (mapId != 0) mapId else (existing?.mapId ?: 0),
            note = if (note.isNotBlank()) note else (existing?.note ?: ""),
        )
        byKey[key] = merged
        return merged
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-overworld-warps.json")
    }
}
