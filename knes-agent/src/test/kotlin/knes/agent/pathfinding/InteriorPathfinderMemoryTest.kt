package knes.agent.pathfinding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.InteriorObservation
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.io.File
import java.nio.file.Files

private fun emptyViewport(
    fill: TileType = TileType.GRASS,
    party: Pair<Int, Int> = 8 to 8,
    edits: (Array<Array<TileType>>) -> Unit = {},
): ViewportMap {
    val tiles = Array(16) { Array(16) { fill } }
    edits(tiles)
    return ViewportMap(tiles, partyLocalXY = party, partyWorldXY = party)
}

private fun freshMemory(): InteriorMemory {
    val dir = Files.createTempDirectory("interior-mem-pf").toFile()
    dir.deleteOnExit()
    return InteriorMemory(File(dir, "mem.json"))
}

/**
 * V5.13: InteriorPathfinder with InteriorMemory targets in this order:
 *   1. EXIT_CONFIRMED memory tile
 *   2. POI_WARP / POI_DOOR memory tile (POI_STAIRS deliberately excluded
 *      after V5.12 castle-stairs evidence)
 *   3. Viewport-classified DOOR / STAIRS / WARP
 *   4. South-edge implicit exit (V5.7 fallback)
 *
 * Without memory, behavior MUST equal V5.7 InteriorPathfinder.
 */
class InteriorPathfinderMemoryTest : FunSpec({

    test("memory POI_DOOR preferred over south-edge fallback") {
        // South-edge implicit exit at (8, 14) (6 SOUTH steps).
        // POI_DOOR recorded at world (10, 8) — 2 EAST steps.
        val vp = emptyViewport(fill = TileType.GRASS) { tiles ->
            for (x in 0 until 16) tiles[15][x] = TileType.UNKNOWN
        }
        val mem = freshMemory()
        mem.record(8, 10, 8, InteriorObservation.POI_DOOR)
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 2
        res.reachedTile shouldBe (10 to 8)
    }

    test("memory EXIT_CONFIRMED preferred over POI_DOOR even if farther") {
        // POI_DOOR at (10, 8) — 2 EAST steps.
        // EXIT_CONFIRMED at (8, 12) — 4 SOUTH steps. Farther but higher priority.
        val vp = emptyViewport(fill = TileType.GRASS)
        val mem = freshMemory()
        mem.record(8, 10, 8, InteriorObservation.POI_DOOR)
        mem.record(8, 8, 12, InteriorObservation.EXIT_CONFIRMED)
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.reachedTile shouldBe (8 to 12)
        res.steps.size shouldBe 4
    }

    test("V5.13 POI_STAIRS is NOT targeted as exit; falls through to south-edge") {
        // South-edge at (8, 14) (6 SOUTH steps).
        // POI_STAIRS at (10, 8) — 2 EAST steps. With V5.10 it would have won;
        // with V5.13 POI_STAIRS is excluded from targets and pathfinder falls
        // through to south-edge.
        val vp = emptyViewport(fill = TileType.GRASS) { tiles ->
            for (x in 0 until 16) tiles[15][x] = TileType.UNKNOWN
        }
        val mem = freshMemory()
        mem.record(8, 10, 8, InteriorObservation.POI_STAIRS)
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 6
        // South-edge implicit exit is at (8, 14).
        res.reachedTile shouldBe (8 to 14)
    }

    test("V5.13 POI_STAIRS does not block POI_DOOR target") {
        // POI_DOOR at (8, 12) — 4 SOUTH steps.
        // POI_STAIRS at (10, 8) — 2 EAST steps but ignored.
        // Pathfinder must pick POI_DOOR.
        val vp = emptyViewport(fill = TileType.GRASS)
        val mem = freshMemory()
        mem.record(8, 10, 8, InteriorObservation.POI_STAIRS)  // closer but ignored
        mem.record(8, 8, 12, InteriorObservation.POI_DOOR)    // farther, wins
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.reachedTile shouldBe (8 to 12)
        res.steps.size shouldBe 4
    }

    test("memory ignored when mapId differs from provider") {
        // POI_STAIRS recorded under mapId=5, but provider returns mapId=8.
        // Viewport has no exits → should fall back to blocked (no result).
        val vp = emptyViewport(fill = TileType.GRASS)
        val mem = freshMemory()
        mem.record(5, 10, 8, InteriorObservation.POI_STAIRS)
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe false
    }

    test("falls back to viewport DOOR if memory POI is unreachable") {
        // Surround memory POI tile with walls; viewport DOOR remains reachable.
        val vp = emptyViewport(fill = TileType.GRASS) { tiles ->
            // Block off (4,4) with walls so memory POI there is unreachable.
            tiles[3][4] = TileType.MOUNTAIN
            tiles[5][4] = TileType.MOUNTAIN
            tiles[4][3] = TileType.MOUNTAIN
            tiles[4][5] = TileType.MOUNTAIN
            // Reachable viewport DOOR.
            tiles[12][8] = TileType.DOOR
        }
        val mem = freshMemory()
        mem.record(8, 4, 4, InteriorObservation.POI_STAIRS)
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.reachedTile shouldBe (8 to 12)
    }

    test("no memory → behavior matches V5.7 (south-edge fallback works)") {
        val vp = emptyViewport(fill = TileType.GRASS) { tiles ->
            for (x in 0 until 16) tiles[15][x] = TileType.UNKNOWN
        }
        val pf = InteriorPathfinder()  // no memory
        val res = pf.findPath(8 to 8, 0 to 0, vp, FogOfWar())
        res.found shouldBe true
        res.steps.size shouldBe 6
    }

    test("memory POI in fog-blocked tile is skipped") {
        val vp = emptyViewport(fill = TileType.GRASS) { tiles ->
            tiles[10][8] = TileType.DOOR  // viewport fallback
        }
        val mem = freshMemory()
        mem.record(8, 4, 4, InteriorObservation.POI_STAIRS)
        val fog = FogOfWar().apply { markBlocked(4, 4) }
        val pf = InteriorPathfinder(memory = mem, mapIdProvider = { 8 })
        val res = pf.findPath(8 to 8, 0 to 0, vp, fog)
        res.found shouldBe true
        res.reachedTile shouldBe (8 to 10)
    }
})
