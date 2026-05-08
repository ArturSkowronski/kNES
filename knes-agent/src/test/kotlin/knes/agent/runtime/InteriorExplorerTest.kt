package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import knes.agent.explorer.FakeHaikuConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.perception.FrameChangeDetector
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.skills.InteriorScanner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * Test stubs for adapter interfaces.
 * Used in Task 8 (skeleton) + Task 9 (full loop tests).
 */
class StubWalkInteriorVision(
    private val sequence: List<WalkOutcome>,
) : WalkInteriorVisionAdapter {
    var calls: Int = 0; private set
    override suspend fun step(): WalkOutcome {
        val out = sequence.getOrNull(calls) ?: WalkOutcome.Stuck
        calls++
        return out
    }
}

class StubEmulatorState(
    private val mapId: Int = 8,
    private val partyLocalX: Int = 8,
    private val partyLocalY: Int = 7,
    private val oam: Set<FrameChangeDetector.SpriteSlot>? = emptySet(),
    private val pixels: ByteArray = ByteArray(0),
    private val screenshotB64: String? = "img-base64",
    private val focusedB64: String? = "focused-base64",
) : InteriorEmulatorState {
    override fun captureOam(): Set<FrameChangeDetector.SpriteSlot>? = oam
    override fun capturePixels(): ByteArray = pixels
    override fun captureScreenshotBase64(): String? = screenshotB64
    override fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int): String? = focusedB64
    override fun currentMapId(): Int = mapId
    override fun partyLocalX(): Int = partyLocalX
    override fun partyLocalY(): Int = partyLocalY
}

class InteriorExplorerTest : FunSpec({

    fun newMemory(): LandmarkMemory {
        val tmp = Files.createTempFile("im", ".json").toFile().apply { deleteOnExit() }
        return LandmarkMemory(file = tmp)
    }

    test("Found in iter 1 when goal landmark confirmed on first scan") {
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(listOf(
                HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.9),
            ), 0.002)),
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "ok", 0.005),
            ),
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState(oam = setOf(FrameChangeDetector.SpriteSlot(0, 0xA0, 120, 112)))
        val walk = StubWalkInteriorVision(sequence = listOf(WalkOutcome.Stepped("east")))
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(
                goal = LandmarkKind.NPC_SHOPKEEPER,
                predicate = { it.note.contains("kind=weapon") },
                capSteps = 50,
            )
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.Found>()
        outcome.landmark.kind shouldBe LandmarkKind.NPC_SHOPKEEPER
    }

    test("Found in iter 4 after 3 rejected candidates") {
        // 4 scans: first 3 rejected, 4th confirmed.
        // Use distinct OAM each iter so frame detector triggers.
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("generic_npc", 5, 3, 0.7)), 0.001),
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("generic_npc", 6, 3, 0.7)), 0.001),
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("generic_npc", 7, 3, 0.7)), 0.001),
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.9)), 0.001),
            ),
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Rejected("not it", 0.005),
                HaikuConsult.VerifyResult.Rejected("not it", 0.005),
                HaikuConsult.VerifyResult.Rejected("not it", 0.005),
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "ok", 0.005),
            ),
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        // OAM differs each iter so frame detector triggers each time
        var oamCallCount = 0
        val emu = object : InteriorEmulatorState {
            override fun captureOam(): Set<FrameChangeDetector.SpriteSlot>? {
                oamCallCount++
                return setOf(FrameChangeDetector.SpriteSlot(8 + oamCallCount, 0xC0, 80, 80))
            }
            override fun capturePixels() = ByteArray(0)
            override fun captureScreenshotBase64() = "img"
            override fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int) = "focused"
            override fun currentMapId() = 8
            override fun partyLocalX() = 8
            override fun partyLocalY() = 7
        }
        val walk = StubWalkInteriorVision(sequence = List(10) { WalkOutcome.Stepped("east") })
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(
                LandmarkKind.NPC_SHOPKEEPER,
                { it.note.contains("kind=weapon") },
                capSteps = 50,
            )
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.Found>()
    }

    test("NotFoundCapReached when capSteps reached with zero confirmed") {
        // Frame detector triggers once (first frame); scan returns no candidates.
        // Subsequent iters: OAM unchanged, no scan triggered, walk continues until cap.
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(emptyList(), 0.001)),
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState(oam = emptySet())  // unchanging OAM after first
        val walk = StubWalkInteriorVision(sequence = List(10) { WalkOutcome.Stepped("east") })
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(
                LandmarkKind.NPC_SHOPKEEPER,
                { true },
                capSteps = 5,
            )
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.NotFoundCapReached>()
        outcome.stats.walkSteps shouldBe 5
    }

    test("StuckBailout after 30 consecutive walk STUCK") {
        val haiku = FakeHaikuConsult()
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState()
        val walk = StubWalkInteriorVision(sequence = List(30) { WalkOutcome.Stuck })
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(LandmarkKind.NPC_SHOPKEEPER, { true }, capSteps = 100)
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.StuckBailout>()
        outcome.reason.contains("walk-stuck") shouldBe true
    }

    test("30 consecutive empty Pass 1 returns StuckBailout pass1-degraded") {
        val haiku = FakeHaikuConsult(
            candidatesScans = List(30) { HaikuConsult.CandidatesScan(emptyList(), 0.001) },
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        // OAM differs each iter → frame detector triggers each iter
        var oamCallCount = 0
        val emu = object : InteriorEmulatorState {
            override fun captureOam(): Set<FrameChangeDetector.SpriteSlot> {
                oamCallCount++
                return setOf(FrameChangeDetector.SpriteSlot(8 + oamCallCount, 0xC0, 80, 80))
            }
            override fun capturePixels() = ByteArray(0)
            override fun captureScreenshotBase64() = "img"
            override fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int) = "focused"
            override fun currentMapId() = 8
            override fun partyLocalX() = 8
            override fun partyLocalY() = 7
        }
        val walk = StubWalkInteriorVision(sequence = List(20) { WalkOutcome.Stepped("east") })
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(LandmarkKind.NPC_SHOPKEEPER, { true }, capSteps = 50)
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.StuckBailout>()
        outcome.reason shouldBe "pass1-degraded"
    }

    test("goal predicate filters kind=weapon over kind=armor") {
        // First confirm armor shopkeeper, then weapon shopkeeper. Predicate matches weapon only.
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("shopkeeper", 4, 3, 0.9)), 0.001),
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("shopkeeper", 6, 3, 0.9)), 0.001),
            ),
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "armor", "armor counter", 0.005),
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "weapon counter", 0.005),
            ),
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        var oamCallCount = 0
        val emu = object : InteriorEmulatorState {
            override fun captureOam(): Set<FrameChangeDetector.SpriteSlot> {
                oamCallCount++
                return setOf(FrameChangeDetector.SpriteSlot(8 + oamCallCount, 0xC0, 80, 80))
            }
            override fun capturePixels() = ByteArray(0)
            override fun captureScreenshotBase64() = "img"
            override fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int) = "focused"
            override fun currentMapId() = 8
            override fun partyLocalX() = 8
            override fun partyLocalY() = 7
        }
        val walk = StubWalkInteriorVision(sequence = List(10) { WalkOutcome.Stepped("east") })
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(
                LandmarkKind.NPC_SHOPKEEPER,
                { it.note.contains("kind=weapon") },
                capSteps = 50,
            )
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.Found>()
        outcome.landmark.note.contains("kind=weapon") shouldBe true
    }

    test("EncounterTriggered when walk reports encounter") {
        val haiku = FakeHaikuConsult()
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState()
        val walk = StubWalkInteriorVision(sequence = listOf(
            WalkOutcome.Stepped("east"), WalkOutcome.EncounterStarted,
        ))
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(LandmarkKind.NPC_SHOPKEEPER, { true }, capSteps = 50)
        }
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.EncounterTriggered>()
    }

    test("Found path emits scan_triggered + scan_candidates + scan_confirmed traces") {
        val captured = mutableListOf<String>()
        val sink: InteriorTraceSink = { captured += it }

        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(HaikuConsult.CandidatesScan(listOf(
                HaikuConsult.CandidateLandmark("shopkeeper", 5, 3, 0.9),
            ), 0.002)),
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Confirmed("shopkeeper", "weapon", "ok", 0.005),
            ),
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1", traceSink = sink)
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState(oam = setOf(FrameChangeDetector.SpriteSlot(0, 0xA0, 120, 112)))
        val walk = StubWalkInteriorVision(sequence = listOf(WalkOutcome.Stepped("east")))
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem, traceSink = sink)

        val outcome = runBlocking {
            explorer.exploreUntilFound(
                LandmarkKind.NPC_SHOPKEEPER,
                { it.note.contains("kind=weapon") },
                capSteps = 50,
            )
        }

        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.Found>()
        captured.any { it.startsWith("interior_scan_triggered:") } shouldBe true
        captured.any { it.startsWith("interior_scan_candidates:") } shouldBe true
        captured.any { it.startsWith("interior_scan_confirmed:") } shouldBe true
    }

    test("error result counted but does not bail explorer") {
        // Scan returns 1 candidate; verify returns Errored. Explorer continues.
        val haiku = FakeHaikuConsult(
            candidatesScans = listOf(
                HaikuConsult.CandidatesScan(listOf(
                    HaikuConsult.CandidateLandmark("chest", 2, 2, 0.8)), 0.001),
            ),
            verifyResults = listOf(
                HaikuConsult.VerifyResult.Errored("api-down", 0.0),
            ),
        )
        val mem = newMemory()
        val scanner = InteriorScanner(haiku, mem, "r1")
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState(oam = setOf(FrameChangeDetector.SpriteSlot(0, 0xA0, 120, 112)))
        val walk = StubWalkInteriorVision(sequence = List(5) { WalkOutcome.Stepped("east") })
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        val outcome = runBlocking {
            explorer.exploreUntilFound(LandmarkKind.NPC_SHOPKEEPER, { true }, capSteps = 5)
        }
        // Did NOT bail — reached cap because no shopkeeper found and walk completed all 5 steps.
        outcome.shouldBeInstanceOf<InteriorExplorer.ExploreOutcome.NotFoundCapReached>()
        outcome.stats.errored shouldBe 1
    }
})
