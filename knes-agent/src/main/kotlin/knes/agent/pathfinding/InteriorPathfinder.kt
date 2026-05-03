package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.util.ArrayDeque

/**
 * BFS over the viewport from party position to the nearest tile that is either:
 *  - explicitly classified DOOR / STAIRS / WARP, or
 *  - a "south-edge implicit exit": a passable tile whose immediate SOUTH neighbour
 *    in the viewport is impassable/UNKNOWN (FF1 town/castle exits are implicit at
 *    the south boundary of the playable area — engine handles transition when
 *    party walks off).
 *
 * Goal is "any exit"; the `to` parameter of Pathfinder is ignored.
 */
class InteriorPathfinder(private val maxSteps: Int = 64) : Pathfinder {

    override fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult {
        val start = viewport.worldToLocal(from.first, from.second)
            ?: return PathResult.blocked(from, "from outside viewport", SearchSpace.LOCAL_MAP)

        val w = viewport.width
        val h = viewport.height
        val visited = Array(h) { BooleanArray(w) }
        val viaDir = Array(h) { Array<Direction?>(w) { null } }
        val q = ArrayDeque<Pair<Int, Int>>()
        q.add(start)
        visited[start.second][start.first] = true

        while (q.isNotEmpty()) {
            val (cx, cy) = q.poll()
            val isStart = cx == start.first && cy == start.second
            val type = viewport.tiles[cy][cx]
            val (rwx, rwy) = viewport.localToWorld(cx, cy)

            if (!isStart && (isExitTile(type) || isSouthEdgeExit(viewport, cx, cy))) {
                // Check if this exit is blocked by fog
                if (!fog.isBlocked(rwx, rwy)) {
                    val steps = reconstruct(cx, cy, start, viaDir)
                    if (steps.size > maxSteps) {
                        val truncated = steps.take(maxSteps)
                        return PathResult(true, truncated, reachedAfter(truncated, from),
                            SearchSpace.LOCAL_MAP, partial = true,
                            reason = "exit found but path exceeds $maxSteps steps")
                    }
                    return PathResult(true, steps, rwx to rwy, SearchSpace.LOCAL_MAP, partial = false)
                }
                // Exit is fogged; continue searching for unfogged exits
            }

            for (dir in Direction.values()) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                if (visited[ny][nx]) continue
                val tile = viewport.tiles[ny][nx]
                if (!tile.isPassable() && !isExitTile(tile)) continue
                visited[ny][nx] = true
                viaDir[ny][nx] = dir
                q.add(nx to ny)
            }
        }

        return PathResult.blocked(from,
            "no exit (DOOR/STAIRS/WARP or south-edge) visible in viewport",
            SearchSpace.LOCAL_MAP)
    }

    private fun isExitTile(t: TileType): Boolean =
        t == TileType.DOOR || t == TileType.STAIRS || t == TileType.WARP

    /** South-edge implicit exit: this tile is passable AND every tile in the same
     *  column south of it (within SOUTH_EDGE_PROBE_DEPTH rows) is impassable/UNKNOWN.
     *  This filters out internal walls — only the outer south boundary of the
     *  playable area qualifies. FF1 engine transitions to the parent map when the
     *  party walks SOUTH off the playable area.
     *
     *  V2.6.3: probe depth bounded (originally checked to viewport.height end). With
     *  the V2.6.2 64×64 full-map view that broke the heuristic — distant rows below
     *  the playable area can be GRASS (0x00 fill) which falsely fails the check.
     *  Bounded depth restores 16×16-equivalent semantics while still benefitting from
     *  global BFS reachability. */
    private fun isSouthEdgeExit(viewport: ViewportMap, lx: Int, ly: Int): Boolean {
        val type = viewport.tiles[ly][lx]
        if (!type.isPassable()) return false
        if (ly + 1 >= viewport.height) return false  // Reached map edge — no proof of outside
        val end = minOf(ly + 1 + SOUTH_EDGE_PROBE_DEPTH, viewport.height)
        if (end <= ly + 1) return false
        for (sy in ly + 1 until end) {
            if (viewport.tiles[sy][lx].isPassable()) return false
        }
        return true
    }

    private companion object {
        const val SOUTH_EDGE_PROBE_DEPTH = 8
    }

    private fun reconstruct(
        endX: Int, endY: Int,
        start: Pair<Int, Int>,
        viaDir: Array<Array<Direction?>>,
    ): List<Direction> {
        val out = ArrayDeque<Direction>()
        var cx = endX; var cy = endY
        while (cx != start.first || cy != start.second) {
            val dir = viaDir[cy][cx] ?: break
            out.addFirst(dir)
            cx -= dir.dx; cy -= dir.dy
        }
        return out.toList()
    }

    private fun reachedAfter(steps: List<Direction>, from: Pair<Int, Int>): Pair<Int, Int> {
        var (x, y) = from
        for (d in steps) { x += d.dx; y += d.dy }
        return x to y
    }
}
