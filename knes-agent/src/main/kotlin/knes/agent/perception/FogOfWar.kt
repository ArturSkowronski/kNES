package knes.agent.perception

/**
 * Per-run accumulator of seen tiles and confirmed-blocked tiles.
 * In-memory only (V2.3); cross-run persistence is V2.4.
 */
class FogOfWar {
    private val seen = mutableMapOf<Pair<Int, Int>, TileType>()
    private val blocked = mutableSetOf<Pair<Int, Int>>()

    val size: Int get() = seen.size

    fun merge(viewport: ViewportMap) {
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                val type = viewport.tiles[ly][lx]
                if (type == TileType.UNKNOWN) continue
                seen[viewport.localToWorld(lx, ly)] = type
            }
        }
    }

    fun tileAt(worldX: Int, worldY: Int): TileType =
        seen[worldX to worldY] ?: TileType.UNKNOWN

    fun markBlocked(worldX: Int, worldY: Int) {
        blocked += worldX to worldY
    }

    fun isBlocked(worldX: Int, worldY: Int): Boolean = (worldX to worldY) in blocked

    fun blockedTiles(): Set<Pair<Int, Int>> = blocked.toSet()

    fun clear() {
        seen.clear()
        blocked.clear()
    }

    fun bbox(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (seen.isEmpty()) return null
        val xs = seen.keys.map { it.first }
        val ys = seen.keys.map { it.second }
        return (xs.min() to ys.min()) to (xs.max() to ys.max())
    }
}
