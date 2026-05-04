package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.InteriorObservation
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.util.ArrayDeque

/**
 * BFS over the viewport from party position to the nearest exit. Target
 * priority (V5.10, GPP "Map Markers"-style):
 *   1. Memory-recorded `EXIT_CONFIRMED` (we walked through here and it
 *      flipped mapflags last time).
 *   2. Memory-recorded `POI_STAIRS` / `POI_WARP` / `POI_DOOR`.
 *   3. Viewport-classified `DOOR` / `STAIRS` / `WARP`.
 *   4. South-edge implicit exit (V2.4–V5.7 fallback): a passable tile whose
 *      immediate SOUTH neighbours within `SOUTH_EDGE_PROBE_DEPTH` rows are
 *      impassable/UNKNOWN — i.e. the outer south boundary of the playable
 *      area. FF1 engine transitions to the parent map when the party walks
 *      SOUTH off this row.
 *
 * Without memory, the behaviour is identical to V5.7 (categories 3 and 4
 * only). Goal is "any exit"; the `to` parameter of [Pathfinder] is ignored.
 */
class InteriorPathfinder(
    private val maxSteps: Int = 64,
    private val memory: InteriorMemory? = null,
    private val mapIdProvider: (() -> Int)? = null,
) : Pathfinder {

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

        val mapId = mapIdProvider?.invoke()
        val mem = if (mapId != null) memory else null

        // Per-category nearest-match (BFS poll order = distance order, so
        // first non-null assignment is shortest path to that category).
        var bestExitConfirmed: Pair<Int, Int>? = null
        var bestMemoryPoi: Pair<Int, Int>? = null
        var bestViewportExit: Pair<Int, Int>? = null
        var bestSouthEdge: Pair<Int, Int>? = null

        while (q.isNotEmpty()) {
            val (cx, cy) = q.poll()
            val isStart = cx == start.first && cy == start.second
            val type = viewport.tiles[cy][cx]
            val (rwx, rwy) = viewport.localToWorld(cx, cy)

            if (!isStart && !fog.isBlocked(rwx, rwy)) {
                if (mem != null && mapId != null) {
                    when (mem.get(mapId, rwx, rwy)?.observation) {
                        InteriorObservation.EXIT_CONFIRMED ->
                            if (bestExitConfirmed == null) bestExitConfirmed = cx to cy
                        InteriorObservation.POI_STAIRS,
                        InteriorObservation.POI_WARP,
                        InteriorObservation.POI_DOOR ->
                            if (bestMemoryPoi == null) bestMemoryPoi = cx to cy
                        else -> {}
                    }
                }
                if (isExitTile(type)) {
                    if (bestViewportExit == null) bestViewportExit = cx to cy
                } else if (isSouthEdgeExit(viewport, cx, cy)) {
                    if (bestSouthEdge == null) bestSouthEdge = cx to cy
                }
                // Once highest-priority bucket is found, BFS guarantees it's
                // the shortest route — no later tile can beat it.
                if (bestExitConfirmed != null) break
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

        val target = bestExitConfirmed ?: bestMemoryPoi ?: bestViewportExit ?: bestSouthEdge
            ?: return PathResult.blocked(
                from,
                "no exit (memory POI / DOOR / STAIRS / WARP / south-edge) reachable in viewport",
                SearchSpace.LOCAL_MAP,
            )

        val (tx, ty) = target
        val (rwx, rwy) = viewport.localToWorld(tx, ty)
        val steps = reconstruct(tx, ty, start, viaDir)
        return if (steps.size > maxSteps) {
            val truncated = steps.take(maxSteps)
            PathResult(
                true, truncated, reachedAfter(truncated, from),
                SearchSpace.LOCAL_MAP, partial = true,
                reason = "exit found but path exceeds $maxSteps steps",
            )
        } else {
            PathResult(true, steps, rwx to rwy, SearchSpace.LOCAL_MAP, partial = false)
        }
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
        if (ly + 1 >= viewport.height) return false
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
