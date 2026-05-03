package knes.agent.pathfinding

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportMap
import java.util.PriorityQueue

class ViewportPathfinder(private val maxSteps: Int = 32) : Pathfinder {

    override fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult {
        if (from == to) return PathResult(true, emptyList(), to, SearchSpace.VIEWPORT, partial = false)

        val start = viewport.worldToLocal(from.first, from.second)
            ?: return PathResult.blocked(from, "from outside viewport")
        val targetLocal = viewport.worldToLocal(to.first, to.second)
        val w = viewport.width
        val h = viewport.height
        val INF = Int.MAX_VALUE
        val dist = Array(h) { IntArray(w) { INF } }
        val viaDir = Array(h) { Array<Direction?>(w) { null } }
        // Dijkstra: visit cheapest-cost-so-far first. Cost is incurred on entering a tile,
        // so origin is free (cost=0) and TOWN/CASTLE detours are penalized via TileType.cost().
        val pq = PriorityQueue<IntArray>(compareBy { it[0] })
        dist[start.second][start.first] = 0
        pq.add(intArrayOf(0, start.first, start.second))
        var bestReachable: Pair<Int, Int> = start
        val edgeTarget = targetEdge(viewport, to)
        var bestDistToTargetSq = distSq(start, targetLocal ?: edgeTarget)

        while (pq.isNotEmpty()) {
            val node = pq.poll()
            val cost = node[0]; val cx = node[1]; val cy = node[2]
            if (cost > dist[cy][cx]) continue  // stale entry
            if (targetLocal != null && cx == targetLocal.first && cy == targetLocal.second) {
                val steps = reconstruct(cx, cy, start, viaDir)
                if (steps.size > maxSteps) {
                    val truncated = steps.take(maxSteps)
                    return PathResult(true, truncated, reachedAfter(truncated, from),
                        SearchSpace.VIEWPORT, partial = true, reason = "path exceeds $maxSteps steps")
                }
                return PathResult(true, steps, to, SearchSpace.VIEWPORT, partial = false)
            }
            val candTargetLocal = targetLocal ?: edgeTarget
            val d = distSq(cx to cy, candTargetLocal)
            if (d < bestDistToTargetSq) {
                bestDistToTargetSq = d
                bestReachable = cx to cy
            }
            for (dir in Direction.values()) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val tile = viewport.tiles[ny][nx]
                if (!tile.isPassable()) continue
                val (wx, wy) = viewport.localToWorld(nx, ny)
                if (fog.isBlocked(wx, wy)) continue
                // Destination tile is always cheap: if the caller picked a TOWN/CASTLE
                // as the goal (e.g. shopping), don't penalize entering it. Penalty only
                // applies when town/castle tiles lie *between* origin and goal.
                val tileCost = if (targetLocal != null && nx == targetLocal.first && ny == targetLocal.second) 1
                               else tile.cost()
                val nCost = cost + tileCost
                if (nCost < dist[ny][nx]) {
                    dist[ny][nx] = nCost
                    viaDir[ny][nx] = dir
                    pq.add(intArrayOf(nCost, nx, ny))
                }
            }
        }

        if (targetLocal == null && bestReachable != start) {
            val steps = reconstruct(bestReachable.first, bestReachable.second, start, viaDir).take(maxSteps)
            val (rwx, rwy) = viewport.localToWorld(bestReachable.first, bestReachable.second)
            return PathResult(true, steps, rwx to rwy, SearchSpace.VIEWPORT, partial = true,
                reason = "target outside viewport; walked toward it")
        }
        return PathResult.blocked(from, "no path within viewport")
    }

    private fun targetEdge(vm: ViewportMap, target: Pair<Int, Int>): Pair<Int, Int> {
        val (px, py) = vm.partyLocalXY
        val (wx, wy) = vm.partyWorldXY
        val dx = (target.first - wx).coerceIn(-(px), vm.width - 1 - px)
        val dy = (target.second - wy).coerceIn(-(py), vm.height - 1 - py)
        return px + dx to py + dy
    }

    private fun distSq(a: Pair<Int, Int>, b: Pair<Int, Int>): Int {
        val dx = a.first - b.first; val dy = a.second - b.second
        return dx * dx + dy * dy
    }

    private fun reconstruct(endX: Int, endY: Int, start: Pair<Int, Int>, viaDir: Array<Array<Direction?>>): List<Direction> {
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
