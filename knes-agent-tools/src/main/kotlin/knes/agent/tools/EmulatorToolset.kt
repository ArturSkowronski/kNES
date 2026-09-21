package knes.agent.tools

import knes.agent.tools.results.*

/** Contract for controlling a NES emulator — local in-process or remote over HTTP/MCP. */
interface EmulatorToolset {
    fun loadRom(path: String): StatusResult
    fun reset(): StatusResult
    fun step(buttons: List<String>, frames: Int = 1, screenshot: Boolean = false): StepResult
    fun tap(button: String, count: Int = 1, pressFrames: Int = 5, gapFrames: Int = 15, screenshot: Boolean = false): StepResult
    fun sequence(steps: List<StepEntry>, screenshot: Boolean = false): StepResult
    fun getState(): StateSnapshot
    fun getScreen(): ScreenPng
    fun observe(profileId: String? = null, screenshot: Boolean = false): AgentObservation {
        val state = getState()
        val screen = if (screenshot) getScreen() else null
        return AgentObservationBuilder.from(state, screen, profileId)
    }

    fun applyProfile(id: String): StatusResult
    fun listProfiles(): List<ProfileSummary>
    fun listActions(profileId: String? = null): List<ActionDescriptor>
    fun executeAction(profileId: String, actionId: String): ActionToolResult
    fun press(buttons: List<String>): StatusResult
    fun release(buttons: List<String>): StatusResult

    /**
     * The most recent [count] instructions the CPU executed, oldest first.
     *
     * Tracing is off until something asks for it, so the first call returns what has
     * happened since — nothing. It is a developer-facing window into the emulator, not a
     * gameplay tool, which is why it is a resource rather than a tool.
     */
    fun traceTail(count: Int = 64): List<TracedInstruction> =
        error("instruction trace is only available on an in-process emulator")

    fun saveSavestate(): ByteArray
    fun loadSavestate(bytes: ByteArray): Boolean
    fun advanceFrames(count: Int)

    companion object {
        fun local(): EmulatorToolset = LocalEmulatorToolset(knes.api.EmulatorSession())
        fun remote(url: String): EmulatorToolset = RemoteEmulatorToolset(url)
    }
}
