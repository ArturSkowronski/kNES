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
    fun applyProfile(id: String): StatusResult
    fun listProfiles(): List<ProfileSummary>
    fun listActions(profileId: String? = null): List<ActionDescriptor>
    fun executeAction(profileId: String, actionId: String): ActionToolResult
    fun press(buttons: List<String>): StatusResult
    fun release(buttons: List<String>): StatusResult

    fun saveSavestate(): ByteArray
    fun loadSavestate(bytes: ByteArray): Boolean
    fun advanceFrames(count: Int)

    companion object {
        fun local(): EmulatorToolset = LocalEmulatorToolset(knes.api.EmulatorSession())
        fun remote(url: String): EmulatorToolset = RemoteEmulatorToolset(url)
    }
}
