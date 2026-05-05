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
})
