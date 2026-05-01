package knes.agent.tools

import knes.agent.tools.results.*
import knes.api.ApiController
import knes.api.EmulatorSession
import knes.api.SessionActionController
import knes.api.StepRequest
import knes.debug.GameAction
import knes.debug.GameProfile
import knes.debug.actions.ActionRegistry

/**
 * Single source of truth for kNES tool surface.
 * Consumed in-process by:
 *   - `knes-api` (Ktor handlers delegate here)
 *   - `knes-mcp` (MCP server delegates here, no HTTP)
 *   - `knes-agent` (Koog ToolRegistry registers this directly)
 */
class EmulatorToolset(
    private val session: EmulatorSession,
    private val controller: ApiController = session.controller,
) {
    fun loadRom(path: String): StatusResult {
        val ok = session.loadRom(path)
        return StatusResult(ok, if (ok) "ROM loaded: $path" else "Failed to load ROM: $path")
    }

    fun reset(): StatusResult {
        session.reset()
        return StatusResult(true, "reset")
    }

    fun step(buttons: List<String>, frames: Int = 1, screenshot: Boolean = false): StepResult {
        require(frames in 1..600) { "frames must be 1..600, got $frames" }
        val request = StepRequest(buttons = buttons, frames = frames)
        controller.enqueueSteps(listOf(request)).await()
        return readStepResult(screenshot)
    }

    fun tap(
        button: String,
        count: Int = 1,
        pressFrames: Int = 5,
        gapFrames: Int = 15,
        screenshot: Boolean = false,
    ): StepResult {
        require(count in 1..50) { "count must be 1..50, got $count" }
        val steps = (0 until count).flatMap {
            listOf(
                StepRequest(buttons = listOf(button), frames = pressFrames),
                StepRequest(buttons = emptyList(), frames = gapFrames),
            )
        }
        controller.enqueueSteps(steps).await()
        return readStepResult(screenshot)
    }

    fun sequence(steps: List<StepEntry>, screenshot: Boolean = false): StepResult {
        require(steps.isNotEmpty()) { "sequence requires at least one entry" }
        controller.enqueueSteps(steps.map { StepRequest(it.buttons, it.frames) }).await()
        return readStepResult(screenshot)
    }

    fun getState(): StateSnapshot = StateSnapshot(
        frame = session.frameCount,
        ram = session.readWatchedRam(),
        cpu = session.readCpuRegs(),
        heldButtons = controller.getHeldButtons(),
    )

    fun getScreen(): ScreenPng = ScreenPng(base64 = session.screenshotBase64Png())

    fun applyProfile(id: String): StatusResult {
        val profile = GameProfile.get(id) ?: return StatusResult(false, "Unknown profile: $id")
        session.applyProfile(profile)
        ActionRegistry.ensureLoaded(id)
        return StatusResult(true, "applied: $id")
    }

    fun listProfiles(): List<ProfileSummary> =
        GameProfile.list().map { ProfileSummary(it.id, it.name, it.description) }

    fun listActions(profileId: String? = null): List<ActionDescriptor> {
        val map = if (profileId != null) {
            ActionRegistry.ensureLoaded(profileId)
            mapOf(profileId to GameAction.listForProfile(profileId))
        } else GameAction.listAll()
        return map.flatMap { (pid, actions) ->
            actions.map { ActionDescriptor(it.id, pid, it.description) }
        }
    }

    fun executeAction(profileId: String, actionId: String, args: Map<String, String> = emptyMap()): ActionToolResult {
        ActionRegistry.ensureLoaded(profileId)
        val action = GameAction.get(profileId, actionId)
            ?: return ActionToolResult(false, "Action not found: $profileId/$actionId")
        val actionController = SessionActionController(session)
        val result = action.execute(actionController)
        return ActionToolResult(result.success, result.message, result.state.mapValues { it.value.toString() })
    }

    fun press(buttons: List<String>): StatusResult {
        controller.setButtons(buttons)
        return StatusResult(true, "held: ${controller.getHeldButtons()}")
    }

    fun release(buttons: List<String>): StatusResult {
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
