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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

    test("results carry typed structured content alongside the text block") {
        val server = createMcpServer { RecordingToolset() }

        val state = callTool(server, "get_state", buildJsonObject { })
        state.structuredContent!!["frame"]!!.jsonPrimitive.int shouldBe 7
        state.structuredContent!!["ram"]!!.jsonObject["worldX"]!!.jsonPrimitive.int shouldBe 146

        val observation = callTool(server, "observe", buildJsonObject { put("profile_id", "ff1") })
        observation.structuredContent!!["phase"]!!.jsonPrimitive.content shouldBe "Overworld"
    }

    test("a list result is wrapped, because structured content must be an object") {
        val server = createMcpServer { RecordingToolset() }

        val profiles = callTool(server, "list_profiles", buildJsonObject { })

        profiles.structuredContent!!["profiles"]!!.jsonArray.size shouldBe 1
    }

    test("the text block is unchanged by structured content") {
        // An LLM reading tool results is a client too. Structured content is additive;
        // it must not quietly replace what the text channel used to carry.
        val server = createMcpServer { RecordingToolset() }

        val text = (callTool(server, "get_state", buildJsonObject { }).content.first() as TextContent).text!!

        text shouldContain "\"frame\":7"
        text shouldContain "\"worldX\":146"
    }

    test("a failed call reports the error in both channels") {
        val server = createMcpServer { FailingToolset() }

        val result = callTool(server, "load_rom", buildJsonObject { put("path", "/nope.nes") })

        result.isError shouldBe true
        result.structuredContent!!["ok"]!!.jsonPrimitive.boolean shouldBe false
        (result.content.first() as TextContent).text!! shouldContain "no such rom"
    }
})
