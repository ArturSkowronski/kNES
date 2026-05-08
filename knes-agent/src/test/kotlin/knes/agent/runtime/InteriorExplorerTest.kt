package knes.agent.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import knes.agent.explorer.FakeHaikuConsult
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

    test("skeleton — exploreUntilFound throws NotImplementedError until Task 9") {
        val haiku = FakeHaikuConsult()
        val scanner = InteriorScanner(haiku, newMemory(), runId = "r1")
        val frame = FrameChangeDetector()
        val emu = StubEmulatorState()
        val walk = StubWalkInteriorVision(sequence = emptyList())
        val mem = newMemory()
        val explorer = InteriorExplorer(walk, scanner, frame, emu, mem)

        shouldThrow<NotImplementedError> {
            runBlocking {
                explorer.exploreUntilFound(
                    goal = LandmarkKind.NPC_SHOPKEEPER,
                    predicate = { it.note.contains("kind=weapon") },
                    capSteps = 50,
                )
            }
        }
    }
})
