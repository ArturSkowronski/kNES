package knes.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.tools.results.*
import knes.api.ApiController
import knes.api.EmulatorSession
import knes.api.SessionActionController
import knes.api.StepRequest
import knes.debug.GameAction
import knes.debug.GameProfile
import knes.debug.actions.ActionRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** In-process implementation of [EmulatorToolset]. Drives a local [EmulatorSession] directly. */
@LLMDescription("Tools for controlling the kNES emulator: input, screenshots, RAM state, profiles, and registered game actions.")
open class LocalEmulatorToolset(
    val session: EmulatorSession,
    private val controller: ApiController = session.controller,
) : ToolSet, EmulatorToolset {

    override fun saveSavestate(): ByteArray = session.saveState()
    override fun loadSavestate(bytes: ByteArray): Boolean = session.loadState(bytes)
    override fun advanceFrames(count: Int) = session.advanceFrames(count)

    @Tool
    @LLMDescription("Load a NES ROM from the given file path. Requires the Compose UI with embedded API server running on port 6502.")
    override fun loadRom(path: String): StatusResult {
        val ok = session.loadRom(path)
        return StatusResult(ok, if (ok) "ROM loaded: $path" else "Failed to load ROM: $path")
    }

    @Tool
    @LLMDescription("Reset the NES emulator to its initial state")
    override fun reset(): StatusResult {
        session.reset()
        return StatusResult(true, "reset")
    }

    @Tool
    @LLMDescription("Advance emulation by N frames while holding specified buttons. Returns frame count, watched RAM values, and optionally a screenshot.")
    override fun step(buttons: List<String>, frames: Int, screenshot: Boolean): StepResult {
        require(frames in 1..600) { "frames must be 1..600, got $frames" }
        runSteps(listOf(StepRequest(buttons = buttons, frames = frames)))
        return readStepResult(screenshot)
    }

    @Tool
    @LLMDescription("Press a button N times with configurable timing. Equivalent to repeated step(button, press_frames) + step([], gap_frames) cycles. Returns frame count, RAM, and optionally a screenshot.")
    override fun tap(
        button: String,
        count: Int,
        pressFrames: Int,
        gapFrames: Int,
        screenshot: Boolean,
    ): StepResult {
        require(count in 1..50) { "count must be 1..50, got $count" }
        val steps = (0 until count).flatMap {
            listOf(
                StepRequest(buttons = listOf(button), frames = pressFrames),
                StepRequest(buttons = emptyList(), frames = gapFrames),
            )
        }
        runSteps(steps)
        return readStepResult(screenshot)
    }

    @Tool
    @LLMDescription("Execute a sequence of button inputs in one call. Each step holds specified buttons for N frames. Returns frame count, RAM, and optionally a screenshot after all steps complete.")
    override fun sequence(steps: List<StepEntry>, screenshot: Boolean): StepResult {
        require(steps.isNotEmpty()) { "sequence requires at least one entry" }
        runSteps(steps.map { StepRequest(it.buttons, it.frames) })
        return readStepResult(screenshot)
    }

    private fun runSteps(steps: List<StepRequest>) {
        if (session.shared) {
            val latch = controller.enqueueSteps(steps)
            val totalFrames = steps.sumOf { it.frames }
            val timeoutMs = totalFrames * 50L + 5000L
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw TimeoutException(
                    "runSteps timed out waiting for $totalFrames frames (timeout ${timeoutMs}ms)"
                )
            }
        } else {
            for (step in steps) {
                controller.setButtons(step.buttons)
                session.advanceFrames(step.frames)
            }
        }
    }

    @Tool
    @LLMDescription("Get current emulator state: frame count, watched RAM values, CPU registers, and held buttons")
    override fun getState(): StateSnapshot = StateSnapshot(
        frame = session.frameCount,
        ram = session.readWatchedRam(),
        cpu = session.readCpuRegs(),
        heldButtons = controller.getHeldButtons(),
    )

    @Tool
    @LLMDescription("Capture a screenshot of the current NES frame as a base64-encoded PNG image")
    override fun getScreen(): ScreenPng = ScreenPng(base64 = session.screenshotBase64Png())

    @Tool
    @LLMDescription("Apply a game profile (e.g. 'smb' for Super Mario Bros, 'ff1' for Final Fantasy) to enable RAM watching for game-specific variables like HP, gold, position")
    override fun applyProfile(id: String): StatusResult {
        val profile = GameProfile.get(id) ?: return StatusResult(false, "Unknown profile: $id")
        session.applyProfile(profile)
        ActionRegistry.ensureLoaded(id)
        return StatusResult(true, "applied: $id")
    }

    @Tool
    @LLMDescription("List all available game profiles for RAM watching")
    override fun listProfiles(): List<ProfileSummary> =
        GameProfile.list().map { ProfileSummary(it.id, it.name, it.description) }

    @Tool
    @LLMDescription("List available game actions for a profile. Actions are game-specific automation scripts that play like a real NES player — they read the screen and press buttons.")
    override fun listActions(profileId: String?): List<ActionDescriptor> {
        val map = if (profileId != null) {
            ActionRegistry.ensureLoaded(profileId)
            mapOf(profileId to GameAction.listForProfile(profileId))
        } else GameAction.listAll()
        return map.flatMap { (pid, actions) ->
            actions.map { ActionDescriptor(it.id, pid, it.description) }
        }
    }

    @Tool
    @LLMDescription("Execute a game action. Actions play like a real NES player: they read RAM state and press buttons. No memory writes, no cheats. Example: execute_action('ff1', 'battle_fight_all') auto-fights an FF1 battle.")
    override fun executeAction(profileId: String, actionId: String): ActionToolResult {
        ActionRegistry.ensureLoaded(profileId)
        val action = GameAction.get(profileId, actionId)
            ?: return ActionToolResult(false, "Action not found: $profileId/$actionId")
        val actionController = SessionActionController(session)
        val result = action.execute(actionController)
        return ActionToolResult(result.success, result.message, result.state.mapValues { it.value.toString() })
    }

    @Tool
    @LLMDescription("Press and hold one or more buttons (they stay held until released)")
    override fun press(buttons: List<String>): StatusResult {
        controller.setButtons(buttons)
        return StatusResult(true, "held: ${controller.getHeldButtons()}")
    }

    @Tool
    @LLMDescription("Release one or more held buttons")
    override fun release(buttons: List<String>): StatusResult {
        if (buttons.isEmpty()) controller.releaseAll()
        else buttons.forEach { controller.releaseButton(controller.resolveButton(it)) }
        return StatusResult(true, "released")
    }

    private fun readStepResult(screenshot: Boolean): StepResult = StepResult(
        frame = session.frameCount,
        ram = session.readWatchedRam(),
        heldButtons = controller.getHeldButtons(),
        screenshot = if (screenshot) session.screenshotBase64Png() else null,
    )
}
