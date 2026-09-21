package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import knes.agent.tools.EmulatorToolset
import kotlinx.coroutines.runBlocking

private fun readResource(server: io.modelcontextprotocol.kotlin.sdk.server.Server, uri: String): String =
    runBlocking {
        val resource = server.resources[uri] ?: error("resource '$uri' is not registered")
        val result = resource.readHandler(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
        (result.contents.first() as TextResourceContents).text
    }

class McpResourcesTest : FunSpec({

    test("the server publishes the read-only resources") {
        val server = createMcpServer { RecordingToolset() }
        server.resources.keys shouldContainExactlyInAnyOrder McpResources.uris
    }

    test("registering resources does not touch the backend") {
        var built = 0
        createMcpServer { built++; RecordingToolset() }
        built shouldBe 0
    }

    test("resource reads reuse one backend instead of building an emulator each time") {
        var built = 0
        val server = createMcpServer { built++; RecordingToolset() }

        readResource(server, McpResources.STATE_URI)
        readResource(server, McpResources.STATE_URI)
        readResource(server, McpResources.PROFILES_URI)

        built shouldBe 1
    }

    test("state comes from the backend") {
        val server = createMcpServer { RecordingToolset() }
        val body = readResource(server, McpResources.STATE_URI)
        body shouldContain "\"frame\":7"
        body shouldContain "\"worldX\":146"
    }

    test("watched RAM definitions carry addresses, meaning and the hidden flag") {
        val body = readResource(createMcpServer { RecordingToolset() }, McpResources.WATCHED_RAM_URI)
        body shouldContain "\"ff1\""
        body shouldContain "\"smb\""
        body shouldContain "0x610A"
        // encounterCounter is information a human player cannot see on screen.
        body shouldContain "\"hidden\":true"
    }

    test("semantics are published per profile, and a profile without them is left out") {
        val body = readResource(createMcpServer { RecordingToolset() }, McpResources.SEMANTICS_URI)
        body shouldContain "ff1.coneria"
        body shouldContain "\"phases\""
        body shouldContain "\"signals\""
    }

    test("a resource nobody registered is an error, not an empty answer") {
        val server = createMcpServer { RecordingToolset() }
        server.resources["knes://emulator/nonsense"] shouldBe null
    }

    test("the instruction trace is published for looking at, not as a tool") {
        val server = createMcpServer { RecordingToolset() }

        val body = readResource(server, McpResources.TRACE_URI)

        body shouldContain "\"pc\":49152"
        body shouldContain "\"opcode\":76"
        // No matching tool: the surface is meant to shrink, and this is data, not an action.
        server.tools.keys.none { it.contains("trace") } shouldBe true
    }
})
