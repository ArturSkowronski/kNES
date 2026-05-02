package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap

private fun viewportOf(width: Int = 16, height: Int = 16, partyWorldXY: Pair<Int, Int> = 100 to 100,
                       fill: TileType = TileType.GRASS, edits: (Array<Array<TileType>>) -> Unit = {}): ViewportMap {
    val tiles = Array(height) { Array(width) { fill } }
    edits(tiles)
    return ViewportMap(tiles, partyLocalXY = width / 2 to height / 2, partyWorldXY = partyWorldXY)
}

class ViewportPathfinderTest : FunSpec({
    val pf = ViewportPathfinder()

    test("direct path 4 steps north on open grass") {
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 100 to 96, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps.shouldContainExactly(Direction.N, Direction.N, Direction.N, Direction.N)
        res.reachedTile shouldBe (100 to 96)
        res.searchSpace shouldBe SearchSpace.VIEWPORT
    }

    test("L-shape detour around a single mountain blocking direct path") {
        val vm = viewportOf { tiles -> tiles[7][8] = TileType.MOUNTAIN }
        val res = pf.findPath(from = 100 to 100, to = 100 to 96, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps.size shouldBe 6
    }

    test("fully blocked neighborhood returns not_found") {
        val vm = viewportOf(fill = TileType.GRASS) { tiles ->
            tiles[7][7] = TileType.MOUNTAIN; tiles[7][8] = TileType.MOUNTAIN; tiles[7][9] = TileType.MOUNTAIN
            tiles[8][7] = TileType.MOUNTAIN; tiles[8][9] = TileType.MOUNTAIN
            tiles[9][7] = TileType.MOUNTAIN; tiles[9][8] = TileType.MOUNTAIN; tiles[9][9] = TileType.MOUNTAIN
        }
        val res = pf.findPath(from = 100 to 100, to = 100 to 96, viewport = vm, fog = FogOfWar())
        res.found shouldBe false
        res.steps shouldBe emptyList()
    }

    test("target outside viewport returns partial path toward it") {
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 100 to 80, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe true
        res.reachedTile.second shouldBe 92
    }

    test("target equals origin returns empty path with found=true") {
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 100 to 100, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps shouldBe emptyList()
        res.reachedTile shouldBe (100 to 100)
    }

    test("fog blocked tile overrides passable classifier") {
        val vm = viewportOf()
        val fog = FogOfWar().apply { markBlocked(100, 99) }
        val res = pf.findPath(from = 100 to 100, to = 100 to 98, viewport = vm, fog = fog)
        res.found shouldBe true
        res.steps.size shouldBe 4
    }

    test("path within max steps returns non-partial with correct length") {
        val vm = viewportOf()
        val res = pf.findPath(from = 100 to 100, to = 107 to 100, viewport = vm, fog = FogOfWar())
        res.found shouldBe true
        res.partial shouldBe false
        res.steps.size shouldBe 7
    }
})
