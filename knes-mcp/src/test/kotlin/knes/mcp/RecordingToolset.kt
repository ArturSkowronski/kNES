package knes.mcp

import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.*

/**
 * Records what the MCP handlers ask of the backend. Any toolset works behind the
 * handlers now, which is the point of having one implementation instead of two.
 */
internal class RecordingToolset : EmulatorToolset {
    val calls = mutableListOf<String>()

    override fun loadRom(path: String) = record("loadRom($path)") { StatusResult(true, "loaded $path") }
    override fun reset() = record("reset") { StatusResult(true, "reset") }
    override fun step(buttons: List<String>, frames: Int, screenshot: Boolean) =
        record("step($buttons,$frames)") { StepResult(frame = 1, ram = emptyMap(), heldButtons = emptyList()) }
    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int, screenshot: Boolean) =
        record("tap($button,$count)") { StepResult(frame = 2, ram = emptyMap(), heldButtons = emptyList()) }
    override fun sequence(steps: List<StepEntry>, screenshot: Boolean) =
        record("sequence(${steps.size})") { StepResult(frame = 3, ram = emptyMap(), heldButtons = emptyList()) }
    override fun getState() =
        record("getState") { StateSnapshot(frame = 7, ram = mapOf("worldX" to 146), cpu = emptyMap(), heldButtons = emptyList()) }
    override fun getScreen() = record("getScreen") { ScreenPng(base64 = "cG5n") }
    override fun applyProfile(id: String) = record("applyProfile($id)") { StatusResult(true, "applied") }
    override fun listProfiles() = record("listProfiles") { listOf(ProfileSummary("ff1", "Final Fantasy", "")) }
    override fun listActions(profileId: String?) = record("listActions($profileId)") { emptyList<ActionDescriptor>() }
    override fun executeAction(profileId: String, actionId: String) =
        record("executeAction($profileId,$actionId)") { ActionToolResult(true, "done") }
    override fun press(buttons: List<String>) = record("press($buttons)") { StatusResult(true, "pressed") }
    override fun release(buttons: List<String>) = record("release($buttons)") { StatusResult(true, "released") }

    override fun saveSavestate(): ByteArray = error("not reachable from MCP")
    override fun loadSavestate(bytes: ByteArray): Boolean = error("not reachable from MCP")
    override fun advanceFrames(count: Int) = error("not reachable from MCP")

    private fun <T> record(call: String, result: () -> T): T {
        calls += call
        return result()
    }
}
