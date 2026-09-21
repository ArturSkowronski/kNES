package knes.agent.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import knes.agent.tools.results.StepEntry
import knes.api.EmulatorSession
import knes.api.ReplayEntry
import knes.api.play
import java.io.File

private fun rom() = File("src/test/resources/nestest.nes").absolutePath

private fun session(): EmulatorSession {
    val session = EmulatorSession()
    check(File(rom()).exists()) { "missing fixture: ${rom()}" }
    check(session.loadRom(rom())) { "failed to load nestest.nes" }
    return session
}

/** What a run can be judged on: where it ended and what it wrote. */
private fun EmulatorSession.fingerprint(): Pair<Int, List<Int>> =
    frameCount to (0 until 0x0800).map { readMemory(it) }

class ReplayRecorderTest : FunSpec({

    test("a step is recorded as the frames it holds") {
        val recorder = ReplayRecorder(LocalEmulatorToolset(session()))

        recorder.step(listOf("A"), frames = 7)

        recorder.replay().entries shouldBe listOf(ReplayEntry(7, listOf("A")))
    }

    test("a tap is recorded the way the toolset expands it") {
        val recorder = ReplayRecorder(LocalEmulatorToolset(session()))

        recorder.tap("START", count = 2, pressFrames = 3, gapFrames = 4)

        recorder.replay().entries shouldBe listOf(
            ReplayEntry(3, listOf("START")), ReplayEntry(4, emptyList()),
            ReplayEntry(3, listOf("START")), ReplayEntry(4, emptyList()),
        )
    }

    test("a sequence is recorded step by step") {
        val recorder = ReplayRecorder(LocalEmulatorToolset(session()))

        recorder.sequence(listOf(StepEntry(listOf("UP"), 2), StepEntry(emptyList(), 5)))

        recorder.replay().entries shouldBe listOf(
            ReplayEntry(2, listOf("UP")), ReplayEntry(5, emptyList()),
        )
    }

    test("a held button survives advanceFrames") {
        val recorder = ReplayRecorder(LocalEmulatorToolset(session()))

        recorder.press(listOf("B"))
        recorder.advanceFrames(4)

        recorder.replay().entries shouldBe listOf(ReplayEntry(4, listOf("B")))
    }

    test("a step releases a held button, and the recording says so") {
        // Not what the `press` tool advertises — it claims holds last until released —
        // but it is what the toolset does: step/tap/sequence set the controller's
        // buttons outright, which drops everything else. A recorder that believed the
        // documentation would produce replays that do not reproduce the run.
        val toolset = LocalEmulatorToolset(session())
        val recorder = ReplayRecorder(toolset)

        recorder.press(listOf("B"))
        toolset.getState().heldButtons shouldBe listOf("B")

        recorder.step(listOf("A"), frames = 4)

        toolset.getState().heldButtons shouldBe listOf("A")
        recorder.replay().entries shouldBe listOf(ReplayEntry(4, listOf("A")))
    }

    test("a reset drops what was recorded, because a replay cannot follow it") {
        val recorder = ReplayRecorder(LocalEmulatorToolset(session()))
        recorder.step(listOf("A"), frames = 5)

        recorder.reset()

        recorder.replay().entries shouldBe emptyList()
    }

    test("a recorded trace reproduces the run it came from, with no agent involved") {
        val recorded = session()
        val recorder = ReplayRecorder(LocalEmulatorToolset(recorded))

        recorder.tap("START", count = 2, pressFrames = 3, gapFrames = 4)
        recorder.step(listOf("RIGHT"), frames = 6)
        recorder.press(listOf("A"))
        recorder.advanceFrames(5)
        recorder.release(listOf("A"))
        recorder.step(emptyList(), frames = 3)

        val replayed = session()
        replayed.play(recorder.replay())

        replayed.fingerprint() shouldBe recorded.fingerprint()
    }

    test("the reproduction test would notice a trace with the wrong frame counts") {
        // Guards the test above: if any replay matched any run, "reproduces the run"
        // would be an empty claim.
        val recorded = session()
        val recorder = ReplayRecorder(LocalEmulatorToolset(recorded))
        recorder.tap("START", count = 2, pressFrames = 3, gapFrames = 4)

        val wrong = session()
        wrong.play(knes.api.Replay(entries = listOf(ReplayEntry(10, listOf("START")))))

        wrong.fingerprint() shouldNotBe recorded.fingerprint()
    }
})
