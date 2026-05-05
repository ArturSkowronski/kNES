package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.time.Duration
import kotlin.math.abs

/**
 * "Think like a player" target selector. Returns world (x,y) for the next
 * deterministic walk goal. Priority: unvisited landmarks → salient viewport
 * tiles → frontier → diversify → wander.
 */
class SalienceStrategy(
    private val terrainMemory: OverworldTerrainMemory,
    private val landmarkMemory: LandmarkMemory,
    private val blockageMemory: BlockageMemory,
    private val fog: FogOfWar,
    private val recentFailureWindow: Duration = Duration.ofMinutes(10),
    private val frontierRadius: Int = 20,
) {
    fun pickOverworldTarget(currentXY: Pair<Int, Int>, viewport: ViewportMap): Pair<Int, Int> {
        val recentlyFailed = blockageMemory.recentlyFailedTargets(recentFailureWindow)
        val asKey: (Pair<Int, Int>) -> String = { "${it.first},${it.second}" }

        // Priority 1: unvisited landmarks
        landmarkMemory.findByKind(LandmarkKind.TOWN_ENTRY, LandmarkKind.CASTLE_ENTRY, LandmarkKind.DUNGEON_ENTRY)
            .filter { !it.visited }
            .filter { it.worldX != null && it.worldY != null }
            .filter { (it.worldX!! to it.worldY!!).let { p -> asKey(p) !in recentlyFailed } }
            .minByOrNull { manhattan(currentXY, it.worldX!! to it.worldY!!) }
            ?.let { return it.worldX!! to it.worldY!! }

        // Priority 2: salient unrecorded landmarks in viewport
        val salient = findSalientInViewport(viewport)
            .filter { (wx, wy) -> landmarkMemory.atLocation(wx, wy) == null }
            .filter { asKey(it) !in recentlyFailed }
            .minByOrNull { manhattan(currentXY, it) }
        if (salient != null) {
            // pre-record so future runs prioritize via priority 1
            val kind = guessKindFromTile(viewport.tiles[localY(viewport, salient)][localX(viewport, salient)])
            landmarkMemory.recordIfNew(Landmark(
                id = "${kind.name.lowercase()}_${salient.first}_${salient.second}",
                kind = kind, worldX = salient.first, worldY = salient.second, visited = false,
            ))
            return salient
        }

        // Priority 3: frontier
        terrainMemory.frontierTilesNear(currentXY, frontierRadius)
            .filter { asKey(it) !in recentlyFailed }
            .filter { !fog.isBlocked(it.first, it.second) }
            .minByOrNull { manhattan(currentXY, it) }
            ?.let { return it }

        // Priority 4: diversify
        val recent = blockageMemory.pathTriedRecentDirections(k = 3).toSet()
        val cardinals = listOf("N" to (0 to -1), "E" to (1 to 0), "S" to (0 to 1), "W" to (-1 to 0))
        cardinals.firstOrNull { it.first !in recent }?.let { (_, delta) ->
            return currentXY.first + delta.first * 8 to currentXY.second + delta.second * 8
        }

        // Priority 5: wander — first walkable tile in viewport
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                if (viewport.tiles[ly][lx].isPassable()) {
                    return viewport.localToWorld(lx, ly)
                }
            }
        }
        return currentXY  // truly degenerate
    }

    private fun findSalientInViewport(viewport: ViewportMap): Sequence<Pair<Int, Int>> = sequence {
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                val type = viewport.tiles[ly][lx]
                if (type == TileType.TOWN || type == TileType.CASTLE) {
                    yield(viewport.localToWorld(lx, ly))
                }
            }
        }
    }

    private fun guessKindFromTile(type: TileType): LandmarkKind = when (type) {
        TileType.TOWN -> LandmarkKind.TOWN_ENTRY
        TileType.CASTLE -> LandmarkKind.CASTLE_ENTRY
        else -> LandmarkKind.UNKNOWN
    }

    private fun manhattan(a: Pair<Int, Int>, b: Pair<Int, Int>) =
        abs(a.first - b.first) + abs(a.second - b.second)

    private fun localX(vm: ViewportMap, world: Pair<Int, Int>): Int =
        vm.partyLocalXY.first + (world.first - vm.partyWorldXY.first)
    private fun localY(vm: ViewportMap, world: Pair<Int, Int>): Int =
        vm.partyLocalXY.second + (world.second - vm.partyWorldXY.second)
}
