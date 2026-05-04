package knes.agent.perception

import knes.agent.pathfinding.Direction
import java.util.ArrayDeque

/**
 * Computes the nearest unvisited reachable tile from a starting position
 * within a [ViewportMap]. Used by V5.11 forced-exploration: gives the vision
 * navigator a hint about which direction has more map left to uncover so it
 * can prioritise exploration over premature EXIT.
 *
 * Inspired by GPP "Mental Map" tool (jcz.dev): the agent's prompt was
 * cranked to prioritise covering every fog tile above its primary goal,
 * which transformatively fixed "claims stuck" behaviour. We approximate
 * that by computing the BFS-shortest direction to the nearest unvisited
 * tile and giving the vision navigator that hint as a soft bias.
 */
object InteriorFrontier {
    data class Result(
        /** Cardinal direction of the FIRST step on the BFS path to the frontier. */
        val firstDirection: InteriorMove,
        /** BFS distance (in tiles) from `from` to the frontier tile. */
        val distance: Int,
        /** Frontier tile in viewport-world coords. */
        val frontier: Pair<Int, Int>,
    )

    /**
     * @param viewport tile grid + coord mapping
     * @param visited tiles already covered (in viewport-world coords); the BFS
     *   skips these as candidate frontier (but still walks through them)
     * @param from starting position in viewport-world coords
     * @return nearest unvisited reachable tile result, or null if none found
     */
    fun nearestUnvisited(
        viewport: ViewportMap,
        visited: Set<Pair<Int, Int>>,
        from: Pair<Int, Int>,
    ): Result? {
        val start = viewport.worldToLocal(from.first, from.second) ?: return null
        val w = viewport.width
        val h = viewport.height
        val seen = Array(h) { BooleanArray(w) }
        val viaDir = Array(h) { Array<Direction?>(w) { null } }
        val q = ArrayDeque<Triple<Int, Int, Int>>()  // (lx, ly, dist)
        q.add(Triple(start.first, start.second, 0))
        seen[start.second][start.first] = true

        var foundLx = -1
        var foundLy = -1
        var foundDist = -1

        while (q.isNotEmpty()) {
            val (cx, cy, dist) = q.poll()
            val (rwx, rwy) = viewport.localToWorld(cx, cy)
            val isStart = cx == start.first && cy == start.second
            if (!isStart && (rwx to rwy) !in visited) {
                foundLx = cx; foundLy = cy; foundDist = dist
                break
            }
            for (dir in Direction.values()) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                if (seen[ny][nx]) continue
                if (!viewport.tiles[ny][nx].isPassable()) continue
                seen[ny][nx] = true
                viaDir[ny][nx] = dir
                q.add(Triple(nx, ny, dist + 1))
            }
        }

        if (foundDist < 0) return null

        // Walk back along viaDir to find the FIRST direction taken from start.
        var cx = foundLx
        var cy = foundLy
        var firstDir: Direction? = null
        while (cx != start.first || cy != start.second) {
            val d = viaDir[cy][cx] ?: break
            firstDir = d
            cx -= d.dx; cy -= d.dy
        }
        firstDir ?: return null
        val (fwx, fwy) = viewport.localToWorld(foundLx, foundLy)
        return Result(firstDir.toInteriorMove(), foundDist, fwx to fwy)
    }

    private fun Direction.toInteriorMove(): InteriorMove = when (this) {
        Direction.N -> InteriorMove.NORTH
        Direction.S -> InteriorMove.SOUTH
        Direction.E -> InteriorMove.EAST
        Direction.W -> InteriorMove.WEST
    }
}
