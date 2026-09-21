package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Records what the MCP handlers ask of the backend. Any toolset works behind the
 * handlers now, which is the point of having one implementation instead of two.
 */
private class RecordingToolset : EmulatorToolset {
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

private fun callTool(server: io.modelcontextprotocol.kotlin.sdk.server.Server, name: String, args: JsonObject) =
    runBlocking {
        val tool = server.tools[name] ?: error("tool '$name' is not registered")
        tool.handler(CallToolRequest(CallToolRequestParams(name = name, arguments = args)))
    }

class McpServerToolsTest : FunSpec({

    test("a server over any toolset registers exactly the catalogued tools") {
        val server = createMcpServer { RecordingToolset() }
        server.tools.keys shouldContainExactlyInAnyOrder McpToolCatalog.all.map { it.name }
    }

    test("building a remote server touches no network") {
        // The remote toolset health-checks in its constructor. If the server built it
        // eagerly, --remote would die at startup instead of on the first tool call.
        createRemoteMcpServer("http://localhost:1").tools.keys shouldContainExactlyInAnyOrder
            McpToolCatalog.all.map { it.name }
    }

    test("handlers reach the backend through the toolset port") {
        val backend = RecordingToolset()
        val server = createMcpServer { backend }

        callTool(server, "load_rom", buildJsonObject { put("path", "/roms/ff.nes") })
        callTool(server, "reset", buildJsonObject { })
        callTool(server, "tap", buildJsonObject { put("button", "A"); put("count", 2) })
        callTool(server, "apply_profile", buildJsonObject { put("profile_id", "ff1") })
        callTool(server, "press", buildJsonObject { putJsonArray("buttons") { add("A"); add("B") } })

        backend.calls shouldContainExactlyInAnyOrder listOf(
            "loadRom(/roms/ff.nes)", "reset", "tap(A,2)", "applyProfile(ff1)", "press([A, B])",
        )
    }

    test("a missing required argument is an error, not a backend call") {
        val backend = RecordingToolset()
        val server = createMcpServer { backend }

        val result = callTool(server, "load_rom", buildJsonObject { })

        result.isError shouldBe true
        (result.content.first() as TextContent).text!! shouldContain "path"
        backend.calls shouldBe emptyList()
    }

    test("a wrongly typed argument is an error, not a crash") {
        val backend = RecordingToolset()
        val server = createMcpServer { backend }

        // The schema says buttons is an array; a client that sends a string used to
        // blow up inside the handler with "JsonLiteral is not a JsonArray".
        val result = callTool(server, "press", buildJsonObject { put("buttons", "A,B") })

        result.isError shouldBe true
        (result.content.first() as TextContent).text!! shouldContain "array"
        backend.calls shouldBe emptyList()
    }

    test("observe interprets RAM through the profile it is given") {
        val server = createMcpServer { RecordingToolset() }

        val result = callTool(server, "observe", buildJsonObject { put("profile_id", JsonPrimitive("ff1")) })

        val text = (result.content.first() as TextContent).text!!
        text shouldContain "\"frame\":7"
        text shouldContain "\"worldX\":146"
    }
})
