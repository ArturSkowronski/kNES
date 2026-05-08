package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
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
})
