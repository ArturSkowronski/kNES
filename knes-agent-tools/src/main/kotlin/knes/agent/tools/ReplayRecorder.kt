package knes.agent.tools

import knes.agent.tools.results.StatusResult
import knes.agent.tools.results.StepEntry
import knes.agent.tools.results.StepResult
import knes.api.Replay
import knes.api.ReplayEntry

/**
 * Wraps a toolset and records every frame of input it performs.
 *
 * The point is a trace that can be re-executed **without an LLM**. Turn logs record the
 * tools an agent chose, but those are vision-driven and non-deterministic — replaying
 * them needs the model back. Buttons per frame are the layer below that, and they
 * reproduce a run exactly.
 *
 * [press] holds only survive until the next [step], [tap] or [sequence]: those set the
 * controller's buttons outright, which releases everything else. So a hold is recorded
 * against [advanceFrames] — the one call that moves frames without touching the
 * controller — and dropped otherwise. That mirrors the toolset rather than the `press`
 * tool's documented contract, which claims holds last until released. See the note in
 * docs/architecture-modernization-tasks.md.
 */
class ReplayRecorder(private val delegate: EmulatorToolset) : EmulatorToolset by delegate {

    private val entries = mutableListOf<ReplayEntry>()
    private val held = linkedSetOf<String>()

    /** Frames of input recorded so far. */
    val frames: Int get() = entries.sumOf { it.frames }

    /** The script needed to reproduce everything recorded since the last [reset]. */
    fun replay(): Replay = Replay(entries = entries.toList())

    override fun step(buttons: List<String>, frames: Int, screenshot: Boolean): StepResult {
        setButtons(buttons, frames)
        return delegate.step(buttons, frames, screenshot)
    }

    override fun tap(
        button: String,
        count: Int,
        pressFrames: Int,
        gapFrames: Int,
        screenshot: Boolean,
    ): StepResult {
        // Mirrors how the toolsets expand a tap; if that expansion changes, this has to
        // follow or replays stop reproducing.
        repeat(count) {
            setButtons(listOf(button), pressFrames)
            setButtons(emptyList(), gapFrames)
        }
        return delegate.tap(button, count, pressFrames, gapFrames, screenshot)
    }

    override fun sequence(steps: List<StepEntry>, screenshot: Boolean): StepResult {
        for (step in steps) setButtons(step.buttons, step.frames)
        return delegate.sequence(steps, screenshot)
    }

    override fun advanceFrames(count: Int) {
        // The only call that advances frames without setting the controller, so whatever
        // press() left held is what is actually down.
        record(held.toList(), count)
        delegate.advanceFrames(count)
    }

    override fun press(buttons: List<String>): StatusResult {
        held += buttons.map { it.uppercase() }
        return delegate.press(buttons)
    }

    override fun release(buttons: List<String>): StatusResult {
        held -= buttons.map { it.uppercase() }.toSet()
        return delegate.release(buttons)
    }

    /**
     * Anything that moves the machine somewhere a replay cannot follow — a reset, a new
     * ROM, a restored savestate — invalidates what came before, so recording restarts.
     */
    override fun reset(): StatusResult = resetRecording { delegate.reset() }

    override fun loadRom(path: String): StatusResult = resetRecording { delegate.loadRom(path) }

    override fun loadSavestate(bytes: ByteArray): Boolean {
        entries.clear()
        held.clear()
        return delegate.loadSavestate(bytes)
    }

    private fun <T> resetRecording(action: () -> T): T {
        entries.clear()
        held.clear()
        return action()
    }

    /** Setting the controller releases every other button, holds included. */
    private fun setButtons(buttons: List<String>, frames: Int) {
        held.clear()
        record(buttons, frames)
    }

    private fun record(buttons: List<String>, frames: Int) {
        if (frames <= 0) return
        entries += ReplayEntry(frames, buttons.map { it.uppercase() }.distinct())
    }
}
