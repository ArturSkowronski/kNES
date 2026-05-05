package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.TileType
import knes.agent.perception.ViewportMap
import java.nio.file.Files

class SalienceStrategyTest : FunSpec({
    fun tmp(name: String) = Files.createTempFile(name, ".json").toFile().apply { deleteOnExit() }

    fun viewportAllGrass(centerWorld: Pair<Int, Int>, size: Int = 16): ViewportMap {
        val tiles = Array(size) { Array(size) { TileType.GRASS } }
        return ViewportMap(tiles, partyLocalXY = size / 2 to size / 2,
            partyWorldXY = centerWorld)
    }

    test("priority 1A: confirmed entry (mapIdInterior set) is chosen even when far") {
        val landmarks = LandmarkMemory(file = tmp("l"))
        // mapIdInterior=8 means handleNewInterior recorded an actual entry → confirmed.
        landmarks.record(Landmark(id = "town", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 200, worldY = 200, mapIdInterior = 8, visited = false))
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (200 to 200)
    }

    test("priority 1B: tile-tagged candidate beyond distance limit is ignored, falls through") {
        val landmarks = LandmarkMemory(file = tmp("l"))
        // Same far coords (200,200) but no mapIdInterior — auto-recorded, never entered.
        landmarks.record(Landmark(id = "stale", kind = LandmarkKind.CASTLE_ENTRY,
            worldX = 200, worldY = 200, visited = false))
        val terrain = OverworldTerrainMemory(file = tmp("t"))
        // Provide a frontier so priority 3 has something to return.
        terrain.record(146, 158, TileType.GRASS)
        terrain.record(147, 158, TileType.GRASS)
        val strategy = SalienceStrategy(
            terrainMemory = terrain,
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        // Should NOT pick (200,200) — distance 96 exceeds tileTaggedDistanceLimit=40.
        // Falls through to priority 3 (frontier) — accepts any local tile, not (200,200).
        (target == 200 to 200) shouldBe false
    }

    test("priority 1B: tile-tagged candidate WITHIN distance limit is still chosen") {
        val landmarks = LandmarkMemory(file = tmp("l"))
        // (160,170) is manhattan distance 14+12=26 from (146,158) — within default limit 40.
        landmarks.record(Landmark(id = "near", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 160, worldY = 170, visited = false))
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (160 to 170)
    }

    test("priority 1A confirmed entry beats priority 1B local candidate when both exist") {
        val landmarks = LandmarkMemory(file = tmp("l"))
        // Local tile-tag at (150,160) — distance 6, would normally win on distance.
        landmarks.record(Landmark(id = "tagged", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 150, worldY = 160, visited = false))
        // Far confirmed entry at (180,180) — distance 56, but has mapIdInterior.
        landmarks.record(Landmark(id = "confirmed", kind = LandmarkKind.CASTLE_ENTRY,
            worldX = 180, worldY = 180, mapIdInterior = 1, visited = false))
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (180 to 180)
    }

    test("priority 5 wander skips recently-failed tile (no infinite re-target loop)") {
        // All grass viewport — priority 5 row-major scan would return local (0,0) =
        // world (138,150) for spawn (146,158). Mark it recently failed; expect a
        // different walkable tile.
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 146 to 158)
        val blockages = BlockageMemory(file = tmp("b"))
        blockages.record(runId = "r", from = 146 to 158, attemptedTo = "138,150",
            result = "stuck at (146,158): no path within viewport")
        // Also mark the priority-4 cardinal candidates (E/N/S/W from currentXY by 8) as
        // recently failed so we deterministically fall through to priority 5.
        for ((d, p) in listOf("N" to (146 to 150), "E" to (154 to 158),
                "S" to (146 to 166), "W" to (138 to 158))) {
            blockages.record(runId = "r", from = 146 to 158,
                attemptedTo = "${p.first},${p.second}",
                result = "stuck: $d")
            blockages.recordRunStartDirection("seed-$d", d)
        }
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = blockages,
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158, viewport = vm)
        // Should NOT be (138,150) (priority 5 row-major first match), since it's recently failed.
        (target == 138 to 150) shouldBe false
        // Should also not be currentXY (degenerate).
        (target == 146 to 158) shouldBe false
    }

    test("priority 4 diversify skips cardinal candidate that's recently failed") {
        val vm = viewportAllGrass(146 to 158)
        val blockages = BlockageMemory(file = tmp("b"))
        // Mark "N" cardinal (146,150) as recently failed.
        blockages.record(runId = "r", from = 146 to 158, attemptedTo = "146,150",
            result = "impassable terrain")
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = blockages,
            fog = FogOfWar(),
        )
        // No landmarks, no salient tiles, no terrain frontier → fall through to priority 4.
        // Priority 4 must skip "N" → (146,150) and pick another cardinal.
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158, viewport = vm)
        (target == 146 to 150) shouldBe false
    }

    test("recently-failed viewport tile is NOT re-picked by priority 2") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        tiles[5][5] = TileType.CASTLE  // local (5,5) → world (143,155) for spawn (146,158)
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 146 to 158)

        val blockages = BlockageMemory(file = tmp("b"))
        // Simulate prior iteration's failure on (143,155).
        blockages.record(runId = "run-1", from = 146 to 158, attemptedTo = "143,155",
            result = "stuck at (146,158): target (143,155) is impassable terrain")
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = blockages,
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158, viewport = vm)
        // recentlyFailed includes (143,155) → priority 2 must skip it. Falls through to
        // priority 4 (diversify) which picks a cardinal — currentXY ± 8.
        (target == 143 to 155) shouldBe false
    }

    test("priority 2: TOWN tile in viewport beats unmapped frontier when no landmark exists") {
        val tiles = Array(16) { Array(16) { TileType.GRASS } }
        tiles[5][5] = TileType.TOWN  // local (5,5)
        val vm = ViewportMap(tiles, partyLocalXY = 8 to 8, partyWorldXY = 146 to 158)

        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158, viewport = vm)
        // local (5,5), partyLocal (8,8), partyWorld (146,158) → world = (146-3, 158-3) = (143,155)
        target shouldBe (143 to 155)
    }

    test("priority 3: frontier when no salient tiles, no landmarks") {
        val terrain = OverworldTerrainMemory(file = tmp("t"))
        terrain.record(146, 158, TileType.GRASS)
        terrain.record(147, 158, TileType.GRASS)  // frontier — has UNKNOWN neighbours
        val strategy = SalienceStrategy(
            terrainMemory = terrain,
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        // 146,158 itself has UNKNOWN neighbours too; closest with frontier is current — fallback to itself
        // We expect a real frontier tile. 146,158 yields itself (distance 0). That's the chosen target.
        target.first shouldBe 146
        target.second shouldBe 158
    }

    test("priority 0: known warp not yet entered this run is targeted, beating priority 1A") {
        // Both a confirmed entry (priority 1A, distance 50) and a known warp (priority 0,
        // distance 6) exist; priority 0 must win because OverworldWarpMemory carries
        // cross-session evidence the warp triggers an interior, while a confirmed entry
        // at (200,200) might still need the matching warp tile to be walked over.
        val landmarks = LandmarkMemory(file = tmp("l"))
        landmarks.record(Landmark(id = "far_confirmed", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 200, worldY = 200, mapIdInterior = 8, visited = false))
        val warps = OverworldWarpMemory(file = tmp("w"))
        warps.record(worldX = 145, worldY = 152, mapId = 8)
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
            warpMemory = warps,
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (145 to 152)
    }

    test("priority 0: warp already entered this run is skipped — falls through to next priority") {
        val landmarks = LandmarkMemory(file = tmp("l"))
        landmarks.record(Landmark(id = "far_confirmed", kind = LandmarkKind.TOWN_ENTRY,
            worldX = 200, worldY = 200, mapIdInterior = 8, visited = false))
        val warps = OverworldWarpMemory(file = tmp("w"))
        warps.record(worldX = 145, worldY = 152, mapId = 8)
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = landmarks,
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
            warpMemory = warps,
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158),
            enteredWarpsThisRun = setOf(145 to 152))
        // (145,152) is excluded; falls through to priority 1A (confirmed entry at 200,200).
        target shouldBe (200 to 200)
    }

    test("priority 0: warp in recentlyFailed is still retried — only enteredWarpsThisRun gates it") {
        // Post-loadState the emulator drops a few input frames (V5.2 "input not
        // responding"). On run 1 the very first attempt at the closest warp fails
        // and the warp lands in recentlyFailed (10-minute global window). If we
        // filtered priority 0 by recentlyFailed, every subsequent run for the next
        // 10 minutes would skip the real Coneria warp and idle out. enteredWarps-
        // ThisRun is the correct gate.
        val warps = OverworldWarpMemory(file = tmp("w"))
        warps.record(worldX = 145, worldY = 152, mapId = 8)
        val blockages = BlockageMemory(file = tmp("b"))
        blockages.record(runId = "prev", from = 146 to 158, attemptedTo = "145,152",
            result = "input not responding")
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = blockages,
            fog = FogOfWar(),
            warpMemory = warps,
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (145 to 152)
    }

    test("priority 0: closest of multiple warps wins") {
        val warps = OverworldWarpMemory(file = tmp("w"))
        warps.record(worldX = 145, worldY = 152) // distance 7
        warps.record(worldX = 100, worldY = 100) // distance 104
        warps.record(worldX = 150, worldY = 160) // distance 6
        val strategy = SalienceStrategy(
            terrainMemory = OverworldTerrainMemory(file = tmp("t")),
            landmarkMemory = LandmarkMemory(file = tmp("l")),
            blockageMemory = BlockageMemory(file = tmp("b")),
            fog = FogOfWar(),
            warpMemory = warps,
        )
        val target = strategy.pickOverworldTarget(currentXY = 146 to 158,
            viewport = viewportAllGrass(146 to 158))
        target shouldBe (150 to 160)
    }
})
