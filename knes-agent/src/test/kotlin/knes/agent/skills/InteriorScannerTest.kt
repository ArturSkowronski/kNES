package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class InteriorScannerTest : FunSpec({

    fun newMemory(): LandmarkMemory {
        val tmp = Files.createTempFile("im", ".json").toFile().apply { deleteOnExit() }
        return LandmarkMemory(file = tmp)
    }

    test("scanCandidates filters confidence below 0.5") {
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(
                HaikuConsult.CandidatesScan(
                    listOf(
                        HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.8),
                        HaikuConsult.CandidateLandmark("generic_npc", 7, 4, 0.4),  // filtered
                    ),
                    0.002,
                ),
            ),
        )
        val scanner = InteriorScanner(haiku, newMemory(), runId = "r1")
        val out = runBlocking { scanner.scanCandidates("img-base64") }
        out.candidates.size shouldBe 1
        out.candidates[0].kind shouldBe "shopkeeper"
        out.costUsd shouldBe 0.002
    }

    test("scanCandidates with empty result returns empty list") {
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(emptyList(), 0.001)),
        )
        val scanner = InteriorScanner(haiku, newMemory(), runId = "r1")
        runBlocking { scanner.scanCandidates("img").candidates } shouldBe emptyList()
    }

    test("verifyAndPersist confirmed shopkeeper writes Landmark with kind=weapon note") {
        val tmp = Files.createTempFile("im", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        val haiku = FakeHaikuConsult(
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "counter shows swords", 0.005),
            ),
        )
        val scanner = InteriorScanner(haiku, mem, runId = "r1")
        val candidate = HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.8)
        val partyLocalX = 8
        val partyLocalY = 7
        val mapId = 8

        val result = runBlocking {
            scanner.verifyAndPersist(
                focusedScreenshotBase64 = "img",
                candidate = candidate,
                mapId = mapId,
                partyLocalX = partyLocalX,
                partyLocalY = partyLocalY,
            )
        }

        (result is InteriorScanner.PersistResult.Confirmed) shouldBe true
        val persisted = mem.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        persisted.size shouldBe 1
        persisted[0].note.contains("kind=weapon") shouldBe true
        persisted[0].mapId shouldBe mapId
    }

    test("verifyAndPersist rejected does not persist") {
        val tmp = Files.createTempFile("im", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        val haiku = FakeHaikuConsult(
            verifyResults = listOf(HaikuConsult.VerifyResult.Rejected("not a shopkeeper", 0.005)),
        )
        val scanner = InteriorScanner(haiku, mem, runId = "r1")
        val candidate = HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.8)

        val result = runBlocking {
            scanner.verifyAndPersist("img", candidate, 8, 8, 7)
        }
        (result is InteriorScanner.PersistResult.Rejected) shouldBe true
        mem.findByKind(LandmarkKind.NPC_SHOPKEEPER).size shouldBe 0
    }

    test("verifyAndPersist with errored result returns Errored without persisting") {
        val tmp = Files.createTempFile("im", ".json").toFile().apply { deleteOnExit() }
        val mem = LandmarkMemory(file = tmp)
        val haiku = FakeHaikuConsult(
            verifyResults = listOf(HaikuConsult.VerifyResult.Errored("api-down", 0.0)),
        )
        val scanner = InteriorScanner(haiku, mem, runId = "r1")
        val candidate = HaikuConsult.CandidateLandmark("chest", 2, 2, 0.9)
        val result = runBlocking {
            scanner.verifyAndPersist("img", candidate, 8, 8, 7)
        }
        (result is InteriorScanner.PersistResult.Errored) shouldBe true
        mem.findByKind(LandmarkKind.CHEST).size shouldBe 0
    }

    test("kindStringToEnum maps all 9 kinds correctly") {
        InteriorScanner.kindStringToEnum("shopkeeper") shouldBe LandmarkKind.NPC_SHOPKEEPER
        InteriorScanner.kindStringToEnum("king") shouldBe LandmarkKind.NPC_KING
        InteriorScanner.kindStringToEnum("innkeeper") shouldBe LandmarkKind.NPC_INNKEEPER
        InteriorScanner.kindStringToEnum("generic_npc") shouldBe LandmarkKind.NPC_GENERIC
        InteriorScanner.kindStringToEnum("stairs_up") shouldBe LandmarkKind.STAIRS_UP
        InteriorScanner.kindStringToEnum("stairs_down") shouldBe LandmarkKind.STAIRS_DOWN
        InteriorScanner.kindStringToEnum("chest") shouldBe LandmarkKind.CHEST
        InteriorScanner.kindStringToEnum("sign") shouldBe LandmarkKind.SIGN
        InteriorScanner.kindStringToEnum("exit_tile") shouldBe LandmarkKind.EXIT_TILE
        InteriorScanner.kindStringToEnum("anything-else") shouldBe LandmarkKind.UNKNOWN
    }
})
