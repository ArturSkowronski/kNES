package knes.agent.perception

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent overworld terrain map across explorer runs. Per-run [FogOfWar.seen]
 * is in-memory; this class merges it to disk so the next run starts knowing
 * everything the previous run mapped.
 *
 * Storage path: ~/.knes/ff1-overworld-terrain.json (override via constructor for tests).
 */
@Serializable
private data class TerrainFile(
    val version: Int = 1,
    val tiles: Map<String, String> = emptyMap(),  // "x,y" -> TileType.name
)

class OverworldTerrainMemory(
    private val file: File = defaultFile(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val seen: MutableMap<Pair<Int, Int>, TileType> = mutableMapOf()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = json.decodeFromString(TerrainFile.serializer(), file.readText())
            for ((key, name) in parsed.tiles) {
                val (x, y) = key.split(",").map { it.toInt() }
                val type = runCatching { TileType.valueOf(name) }.getOrNull() ?: continue
                seen[x to y] = type
            }
        } catch (e: Exception) {
            System.err.println("[overworld-terrain-memory] failed to load ${file.path}: ${e.message}")
        }
    }

    fun save() {
        val payload = TerrainFile(
            tiles = seen.entries
                .sortedWith(compareBy({ it.key.second }, { it.key.first }))
                .associate { (k, v) -> "${k.first},${k.second}" to v.name },
        )
        AtomicJsonWriter.write(file, json.encodeToString(TerrainFile.serializer(), payload))
    }

    fun record(worldX: Int, worldY: Int, type: TileType) {
        if (type == TileType.UNKNOWN) return
        seen[worldX to worldY] = type
    }

    fun tileAt(worldX: Int, worldY: Int): TileType? = seen[worldX to worldY]

    fun seenCount(): Int = seen.size

    /** Merge UNKNOWN-filtered viewport into memory; returns count of NEWLY added tiles. */
    fun merge(viewport: ViewportMap): Int {
        var added = 0
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                val type = viewport.tiles[ly][lx]
                if (type == TileType.UNKNOWN) continue
                val world = viewport.localToWorld(lx, ly)
                if (seen[world] == null) added++
                seen[world] = type
            }
        }
        return added
    }

    fun bbox(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (seen.isEmpty()) return null
        val xs = seen.keys.map { it.first }; val ys = seen.keys.map { it.second }
        return (xs.min() to ys.min()) to (xs.max() to ys.max())
    }

    /** Tiles that are known + passable + have at least one UNKNOWN cardinal neighbour. */
    fun frontierTilesNear(center: Pair<Int, Int>, radius: Int): Sequence<Pair<Int, Int>> = sequence {
        for ((tile, type) in seen) {
            val dx = tile.first - center.first; val dy = tile.second - center.second
            if (dx * dx + dy * dy > radius * radius) continue
            if (!type.isPassable()) continue
            for (d in arrayOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val n = tile.first + d.first to tile.second + d.second
                if (seen[n] == null) { yield(tile); break }
            }
        }
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".knes/ff1-overworld-terrain.json")
    }
}
