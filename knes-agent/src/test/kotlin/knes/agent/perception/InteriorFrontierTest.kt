package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private fun viewport(
    fill: TileType = TileType.GRASS,
    party: Pair<Int, Int> = 8 to 8,
    edits: (Array<Array<TileType>>) -> Unit = {},
): ViewportMap {
    val tiles = Array(16) { Array(16) { fill } }
    edits(tiles)
    return ViewportMap(tiles, partyLocalXY = party, partyWorldXY = party)
}

class InteriorFrontierTest : FunSpec({

    test("nearest unvisited is the next tile north when only NORTH unvisited") {
        val vp = viewport()
        // Mark all tiles in 5x5 around party as visited EXCEPT (8, 7) which is north of party.
        val visited = mutableSetOf<Pair<Int, Int>>()
        for (x in 6..10) for (y in 6..10) {
            if (!(x == 8 && y == 7)) visited.add(x to y)
        }
        val r = InteriorFrontier.nearestUnvisited(vp, visited, from = 8 to 8)
        r?.firstDirection shouldBe InteriorMove.NORTH
        r?.distance shouldBe 1
    }

    test("returns null when no passable unvisited tile exists in reachable area") {
        // Tiny island: party at (8,8) surrounded by walls. No unvisited reachable.
        val vp = viewport(fill = TileType.MOUNTAIN) { tiles ->
            tiles[8][8] = TileType.GRASS  // only party tile
        }
        val visited = setOf(8 to 8)
        val r = InteriorFrontier.nearestUnvisited(vp, visited, from = 8 to 8)
        r.shouldBeNull()
    }

    test("BFS prefers shortest unvisited path over farther") {
        val vp = viewport()
        // Mark party tile + everything in row 8 visited; row 7 west has unvisited.
        val visited = mutableSetOf<Pair<Int, Int>>()
        for (x in 0 until 16) visited.add(x to 8)
        // (7, 7) is 2 steps away (NORTH then WEST or WEST then NORTH);
        // (10, 7) is also 2+ steps; pick the shortest.
        val r = InteriorFrontier.nearestUnvisited(vp, visited, from = 8 to 8)
        r?.distance shouldBe 1
        // Direction is whichever cardinal neighbour is unvisited and reachable.
        r?.firstDirection shouldBe InteriorMove.NORTH
    }

    test("walls block path to unvisited frontier") {
        val vp = viewport { tiles ->
            // Wall row at y=7 spans entire width — frontier north unreachable.
            for (x in 0 until 16) tiles[7][x] = TileType.MOUNTAIN
        }
        // (8, 6) unvisited but unreachable. (8, 9) reachable + unvisited.
        val visited = setOf(8 to 8)
        val r = InteriorFrontier.nearestUnvisited(vp, visited, from = 8 to 8)
        r?.firstDirection shouldBe InteriorMove.SOUTH
        r?.distance shouldBe 1
    }

    test("from tile outside viewport returns null") {
        val vp = viewport(party = 8 to 8)
        val r = InteriorFrontier.nearestUnvisited(vp, emptySet(), from = 100 to 100)
        r.shouldBeNull()
    }
})
