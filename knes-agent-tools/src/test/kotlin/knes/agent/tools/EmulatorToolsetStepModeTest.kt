package knes.agent.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import knes.agent.tools.results.StepEntry
import knes.api.EmulatorSession
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Unit tests proving that EmulatorToolset.step / sequence work correctly in standalone mode
 * (no external frame driver — must not deadlock).
 *
 * ROM used: nestest.nes (bundled in test resources, freely distributable CPU-test ROM).
 */
class EmulatorToolsetStepModeTest : FunSpec({

    fun loadNestestRom(): String? {
        val url = EmulatorToolsetStepModeTest::class.java.classLoader.getResource("nestest.nes")
        return url?.let { File(it.toURI()).absolutePath }
    }

    fun skipIfNoRom(romPath: String?) {
        if (romPath == null) {
            throw io.kotest.engine.TestAbortedException(
                "nestest.nes not found in test resources — skipping standalone-mode tests"
            )
        }
    }

    test("standalone mode: step(A, frames=5) advances frame count and returns within 1 second") {
        val romPath = loadNestestRom()
        skipIfNoRom(romPath)

        val session = EmulatorSession()
        session.loadRom(romPath!!)

        val toolset = EmulatorToolset(session)
        val frameBefore = session.frameCount

        val elapsed = measureTime {
            toolset.step(listOf("A"), frames = 5)
        }

        session.frameCount shouldBeGreaterThanOrEqual (frameBefore + 5)
        elapsed shouldBe (elapsed) // just verify it returned
        check(elapsed < 1.seconds) { "step took too long: $elapsed (expected < 1s)" }
    }

    test("standalone mode: sequence([A×3, B×3]) advances frame count by exactly 6") {
        val romPath = loadNestestRom()
        skipIfNoRom(romPath)

        val session = EmulatorSession()
        session.loadRom(romPath!!)

        val toolset = EmulatorToolset(session)
        val frameBefore = session.frameCount

        val elapsed = measureTime {
            toolset.sequence(
                listOf(
                    StepEntry(listOf("A"), 3),
                    StepEntry(listOf("B"), 3),
                )
            )
        }

        session.frameCount shouldBe (frameBefore + 6)
        check(elapsed < 1.seconds) { "sequence took too long: $elapsed (expected < 1s)" }
    }

    test("standalone mode: tap(A, count=2, pressFrames=3, gapFrames=3) advances frame count by 12") {
        val romPath = loadNestestRom()
        skipIfNoRom(romPath)

        val session = EmulatorSession()
        session.loadRom(romPath!!)

        val toolset = EmulatorToolset(session)
        val frameBefore = session.frameCount

        val elapsed = measureTime {
            toolset.tap("A", count = 2, pressFrames = 3, gapFrames = 3)
        }

        // 2 taps × (3 press + 3 gap) = 12 frames
        session.frameCount shouldBe (frameBefore + 12)
        check(elapsed < 1.seconds) { "tap took too long: $elapsed (expected < 1s)" }
    }
})
