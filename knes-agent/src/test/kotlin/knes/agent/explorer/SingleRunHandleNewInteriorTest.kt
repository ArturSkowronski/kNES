package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class SingleRunHandleNewInteriorTest : FunSpec({
    test("classifyInterior result is recorded into LandmarkMemory") {
        val landmarks = LandmarkMemory(file = Files.createTempFile("l", ".json").toFile().apply { deleteOnExit() })
        val fake = FakeHaikuConsult(
            interiorClassifications = listOf(
                HaikuConsult.InteriorClassification(
                    landmarks = listOf(
                        Landmark(id = "test_king", kind = LandmarkKind.NPC_KING,
                            mapId = 1, localX = 14, localY = 4, note = "throne", visited = true)
                    ),
                    costUsd = 0.012,
                )
            )
        )

        // Direct call into the helper without spinning up full SingleRun.
        val classification = runBlocking {
            fake.classifyInterior(mapId = 1, visitedTileCount = 30, screenshotBase64 = null)
        }
        val cost = SingleRun.applyInteriorClassification(landmarks, classification)
        cost shouldBe 0.012
        landmarks.findByKind(LandmarkKind.NPC_KING) shouldHaveSize 1
        landmarks.findByKind(LandmarkKind.NPC_KING).first().visited shouldBe true
    }

    test("decideClassification skips when party HP is zero (game over)") {
        // PartyWipe during exploreInteriorFrontier returns the player to the
        // title screen; getScreen() then captures a frame Haiku misreads as
        // interior content (e.g. "person sprite next to NEW GAME text").
        val ram = mapOf(
            "mapflags" to 1, "currentMapId" to 8,
            "char1_hpLow" to 0, "char2_hpLow" to 0,
            "char3_hpLow" to 0, "char4_hpLow" to 0,
        )
        SingleRun.decideClassification(triggerMapId = 8, ramAfterExplore = ram) shouldBe null
    }

    test("decideClassification skips when mapflags=0 (engine returned to overworld)") {
        val ram = mapOf(
            "mapflags" to 0, "currentMapId" to 8,
            "char1_hpLow" to 35, "char2_hpLow" to 30,
            "char3_hpLow" to 28, "char4_hpLow" to 25,
        )
        SingleRun.decideClassification(triggerMapId = 8, ramAfterExplore = ram) shouldBe null
    }

    test("decideClassification uses live RAM mapId when engine completed multi-stage warp") {
        // (145,152) Coneria warp: trigger fires at transient mapId=8 outer
        // overlay; RAM stabilises to inner mapId=24 by the time we reach the
        // post-explore snapshot. Haiku must tag with 24, not 8.
        val ram = mapOf(
            "mapflags" to 1, "currentMapId" to 24,
            "char1_hpLow" to 35, "char2_hpLow" to 30,
            "char3_hpLow" to 28, "char4_hpLow" to 25,
        )
        SingleRun.decideClassification(triggerMapId = 8, ramAfterExplore = ram) shouldBe
            ClassifyDecision(mapIdToUse = 24)
    }

    test("decideClassification falls back to trigger mapId when RAM unreadable") {
        val ram = mapOf(
            "mapflags" to 1,
            "char1_hpLow" to 35, "char2_hpLow" to 30,
            "char3_hpLow" to 28, "char4_hpLow" to 25,
        )
        SingleRun.decideClassification(triggerMapId = 8, ramAfterExplore = ram) shouldBe
            ClassifyDecision(mapIdToUse = 8)
    }
})
