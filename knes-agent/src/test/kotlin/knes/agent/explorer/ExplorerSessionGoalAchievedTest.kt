package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import java.nio.file.Files

class ExplorerSessionGoalAchievedTest : FunSpec({
    test("goalsAchieved returns true when both King and Shopkeeper are visited") {
        val mem = LandmarkMemory(file = Files.createTempFile("l", ".json").toFile().apply { deleteOnExit() })
        ExplorerSession.goalsAchieved(mem) shouldBe false

        mem.record(Landmark(id = "king", kind = LandmarkKind.NPC_KING, mapId = 1, visited = true))
        ExplorerSession.goalsAchieved(mem) shouldBe false  // missing shop

        mem.record(Landmark(id = "shop", kind = LandmarkKind.NPC_SHOPKEEPER, mapId = 8, visited = true))
        ExplorerSession.goalsAchieved(mem) shouldBe true
    }

    test("CoverageStats subtraction returns deltas") {
        val before = CoverageStats(terrainTilesKnown = 50, warpsKnown = 1, landmarksKnown = 0,
            landmarksVisited = 0, interiorMapsExplored = 0)
        val after = CoverageStats(terrainTilesKnown = 87, warpsKnown = 1, landmarksKnown = 1,
            landmarksVisited = 1, interiorMapsExplored = 1)
        val delta = after - before
        delta.terrainTilesKnown shouldBe 37
        delta.landmarksKnown shouldBe 1
        delta.warpsKnown shouldBe 0
        delta.hasNoNewDiscoveries() shouldBe false
    }
})
