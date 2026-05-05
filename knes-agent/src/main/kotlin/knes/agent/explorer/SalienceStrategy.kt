package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.OverworldWarpMemory
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
    private val warpMemory: OverworldWarpMemory? = null,
    private val recentFailureWindow: Duration = Duration.ofMinutes(10),
    private val frontierRadius: Int = 20,
    /** Distance ceiling for tile-tagged landmark candidates (mapIdInterior == null).
     *  Confirmed entries (mapIdInterior != null) ignore this. Default 2 × frontierRadius. */
    private val tileTaggedDistanceLimit: Int = 40,
) {
    fun pickOverworldTarget(
        currentXY: Pair<Int, Int>,
        viewport: ViewportMap,
        enteredWarpsThisRun: Set<Pair<Int, Int>> = emptySet(),
    ): Pair<Int, Int> {
        val recentlyFailed = blockageMemory.recentlyFailedTargets(recentFailureWindow)
        val asKey: (Pair<Int, Int>) -> String = { "${it.first},${it.second}" }

        // Priority 0: known warp tile not yet entered this run. OverworldWarpMemory
        // carries cross-session evidence that the tile triggers an interior — without
        // this, the explorer's deterministic salience plateaus at 17 runs / 0 entries
        // because BFS routes around warp tiles toward viewport TOWN/CASTLE candidates
        // (priority 2) that may be unreachable. Targeting warps directly turns the
        // explorer from lucky discovery into purposeful coverage.
        warpMemory?.all()
            ?.filter { it !in enteredWarpsThisRun }
            ?.filter { asKey(it) !in recentlyFailed }
            ?.minByOrNull { manhattan(currentXY, it) }
            ?.let { return it }

        val unvisited = landmarkMemory.findByKind(
                LandmarkKind.TOWN_ENTRY, LandmarkKind.CASTLE_ENTRY, LandmarkKind.DUNGEON_ENTRY,
            )
            .filter { !it.visited }
            .filter { it.worldX != null && it.worldY != null }
            .filter { (it.worldX!! to it.worldY!!).let { p -> asKey(p) !in recentlyFailed } }

        // Priority 1A: confirmed entries — Landmark.mapIdInterior set by handleNewInterior
        // on a prior run, proving the tile actually leads to an interior. No distance limit.
        unvisited
            .filter { it.mapIdInterior != null }
            .minByOrNull { manhattan(currentXY, it.worldX!! to it.worldY!!) }
            ?.let { return it.worldX!! to it.worldY!! }

        // Priority 1B: tile-tagged candidates — auto-recorded by priority 2 in some prior
        // run; never actually entered. Many may be unreachable from the current zone (other
        // continent, water-locked, etc.). Limit to manhattan ≤ tileTaggedDistanceLimit so
        // stale records on the far side of the world don't starve local exploration.
        unvisited
            .filter { it.mapIdInterior == null }
            .filter { manhattan(currentXY, it.worldX!! to it.worldY!!) <= tileTaggedDistanceLimit }
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
        cardinals.firstOrNull { (dirName, delta) ->
            val candidate = currentXY.first + delta.first * 8 to currentXY.second + delta.second * 8
            dirName !in recent && asKey(candidate) !in recentlyFailed
        }?.let { (_, delta) ->
            return currentXY.first + delta.first * 8 to currentXY.second + delta.second * 8
        }

        // Priority 5: wander — first walkable tile in viewport that hasn't recently failed.
        // Without the recentlyFailed filter this row-major scan deterministically returns
        // the same isPassable()-but-pathfinder-impassable tile every iteration (e.g. world
        // (146,150) for spawn (146,158)) → infinite re-target loop until idle cap fires.
        for (ly in 0 until viewport.height) {
            for (lx in 0 until viewport.width) {
                if (!viewport.tiles[ly][lx].isPassable()) continue
                val world = viewport.localToWorld(lx, ly)
                if (asKey(world) in recentlyFailed) continue
                if (world == currentXY) continue
                return world
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
