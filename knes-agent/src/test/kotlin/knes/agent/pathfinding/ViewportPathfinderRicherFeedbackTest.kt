package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap

/**
 * V5.14: ViewportPathfinder must return GPP-style "richer no-path feedback"
 * instead of bare booleans. When a target is in viewport but its tile is
 * impassable (WATER/MOUNTAIN/UNKNOWN), or when terrain blocks the route,
 * callers need to know:
 *   - was the target itself walkable?
 *   - what is the closest reachable tile we found?
 *   - is there a partial path we can follow toward that closest tile?
 *
 * Without this signal, the agent cannot tell "tool is broken" from "you
 * picked a dumb target" and tends to give up or retry endlessly.
 */
class ViewportPathfinderRicherFeedbackTest : FunSpec({

    fun makeViewport(
        size: Int = 32,
        party: Pair<Int, Int>,
        edits: (Array<Array<TileType>>) -> Unit = {},
    ): ViewportMap {
        val tiles = Array(size) { Array(size) { TileType.GRASS } }
        edits(tiles)
        return ViewportMap(tiles, partyLocalXY = party, partyWorldXY = party)
    }

    val pf = ViewportPathfinder(maxSteps = 64)

    test("WATER target — partial path leads to closest reachable; targetPassable=false") {
        // 32x32 GRASS map, target at (8, 0) is WATER. Party at (8, 16).
        val vp = makeViewport(party = 8 to 16) { tiles ->
            tiles[0][8] = TileType.WATER
        }
        val res = pf.findPath(from = 8 to 16, to = 8 to 0, viewport = vp, fog = FogOfWar())
        // Target is unreachable, but a partial path should walk us to (8, 1).
        res.found shouldBe true
        res.partial shouldBe true
        res.targetPassable shouldBe false
        res.closestReachable shouldNotBeNull {}
        // Closest reachable is the GRASS tile adjacent to the WATER target.
        res.closestReachable shouldBe (8 to 1)
        res.steps.shouldNotBeEmpty()
        res.steps.size shouldBe 15  // 16 → 1
    }

    test("walkable target with reachable path — found=true, partial=false, closestReachable null or absent") {
        val vp = makeViewport(party = 8 to 16)
        val res = pf.findPath(from = 8 to 16, to = 8 to 8, viewport = vp, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.targetPassable shouldBe true
    }

    test("party fully boxed by impassables — blocked + closestReachable == start") {
        val vp = makeViewport(party = 8 to 8) { tiles ->
            tiles[7][8] = TileType.MOUNTAIN
            tiles[9][8] = TileType.MOUNTAIN
            tiles[8][7] = TileType.MOUNTAIN
            tiles[8][9] = TileType.MOUNTAIN
        }
        val res = pf.findPath(from = 8 to 8, to = 8 to 0, viewport = vp, fog = FogOfWar())
        res.found shouldBe false
        res.partial shouldBe false
        res.steps shouldBe emptyList()
        res.closestReachable shouldBe (8 to 8)
    }

    test("walkable target but blocked by terrain wall — partial path bumps into the wall") {
        // Wall row at y=4 spans full width; target at (8, 0) walkable but unreachable.
        val vp = makeViewport(party = 8 to 8) { tiles ->
            for (x in 0 until 32) tiles[4][x] = TileType.MOUNTAIN
        }
        val res = pf.findPath(from = 8 to 8, to = 8 to 0, viewport = vp, fog = FogOfWar())
        res.found shouldBe true  // partial path is "found"
        res.partial shouldBe true
        res.targetPassable shouldBe true  // target itself is fine, just unreachable
        res.closestReachable shouldNotBeNull {}
        res.closestReachable shouldBe (8 to 5)  // bumps into wall row 4 from south
    }
})
