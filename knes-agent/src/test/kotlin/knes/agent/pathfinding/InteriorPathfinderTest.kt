package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap

private fun viewport(
    fill: TileType = TileType.GRASS,
    party: Pair<Int, Int> = 8 to 8,
    edits: (Array<Array<TileType>>) -> Unit = {},
): ViewportMap {
    val tiles = Array(16) { Array(16) { fill } }
    edits(tiles)
    return ViewportMap(tiles, partyLocalXY = party, partyWorldXY = party)
}

class InteriorPathfinderTest : FunSpec({
    val pf = InteriorPathfinder()

    test("direct path to single DOOR in viewport") {
        val vp = viewport { tiles -> tiles[12][8] = TileType.DOOR }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.searchSpace shouldBe SearchSpace.LOCAL_MAP
        res.steps.size shouldBe 4
        res.steps.shouldNotBeEmpty()
    }

    test("prefers nearest exit when multiple exist") {
        val vp = viewport { tiles ->
            tiles[10][8] = TileType.STAIRS
            tiles[14][8] = TileType.DOOR
        }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 2
    }

    test("paths around walls to reach a DOOR") {
        val vp = viewport { tiles ->
            tiles[9][8] = TileType.MOUNTAIN
            tiles[12][8] = TileType.DOOR
        }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        check(res.steps.size >= 5)
    }

    test("WARP tile counts as exit") {
        val vp = viewport { tiles -> tiles[6][8] = TileType.WARP }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 2
    }

    test("south-edge passable tile is implicit exit (no DOOR/STAIRS/WARP needed)") {
        // Build a viewport where party is in floor, walls south, but row 14 has
        // floor and row 15 is UNKNOWN (outside-of-map padding). Going south to
        // row 14 is the implicit exit.
        val vp = viewport(fill = TileType.GRASS) { tiles ->
            for (x in 0 until 16) tiles[15][x] = TileType.UNKNOWN
        }
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        // Best implicit exit is local (8, 14) — the south-most passable row.
        // Path: 6 SOUTH steps from (8,8) to (8,14).
        res.steps.size shouldBe 6
    }

    test("no exit and no south-edge implicit exit -> not_found") {
        // All tiles are GRASS — there is no UNKNOWN/impassable boundary, so no
        // implicit south-edge exit. And no DOOR/STAIRS/WARP either.
        val vp = viewport(fill = TileType.GRASS)
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe false
        res.steps shouldBe emptyList()
    }

    test("fog-blocked exit tile is skipped") {
        val vp = viewport { tiles ->
            tiles[10][8] = TileType.DOOR
            tiles[14][8] = TileType.DOOR
        }
        val fog = FogOfWar().apply { markBlocked(8, 10) }
        val res = pf.findPath(8 to 8, 0 to 0, vp, fog)
        res.found shouldBe true
        res.steps.size shouldBe 6
    }
})
