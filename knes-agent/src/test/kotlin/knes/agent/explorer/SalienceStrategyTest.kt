package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldTerrainMemory
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
})
