package knes.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.LocalEmulatorToolset
import knes.agent.tools.results.StepEntry
import knes.api.EmulatorSession
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP server over an [EmulatorToolset].
 *
 * There is one set of tool handlers. Where the emulator actually runs is the toolset's
 * problem: [createMcpServer] drives one in-process, [createRemoteMcpServer] drives one
 * hosted by the Compose UI over REST. Registering the tools twice, once per transport,
 * is what let the two modes drift apart.
 */
fun createMcpServer(backend: () -> EmulatorToolset): Server {
    // Lazy: building the server must not touch the emulator or the network. The remote
    // toolset health-checks in its constructor, so an eager call would make --remote die
    // at startup instead of on the first tool call.
    val toolset by lazy(backend)

    val server = Server(
        serverInfo = Implementation(
            name = "knes-mcp",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false)
            )
        )
    )

    val json = Json { encodeDefaults = true }

    // `{ toolset }`, not `backend` — reading the lazy val reuses the one instance.
    // Passing the provider itself would build a fresh emulator on every resource read.
    McpResources.register(server, { toolset }, json)

    // 1. load_rom
    server.addTool(
        name = McpToolCatalog.loadRom.name,
        description = McpToolCatalog.loadRom.description,
        inputSchema = McpToolCatalog.loadRom.inputSchema!!
    ) { request ->
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required parameter: path")), isError = true)
        val result = toolset.loadRom(path)
        if (result.ok) {
            CallToolResult(content = listOf(TextContent(result.message)))
        } else {
            CallToolResult(content = listOf(TextContent(result.message)), isError = true)
        }
    }

    // 2. step
    server.addTool(
        name = McpToolCatalog.step.name,
        description = McpToolCatalog.step.description,
        inputSchema = McpToolCatalog.step.inputSchema!!
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val frames = request.arguments?.get("frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val result = toolset.step(buttons, frames, screenshot)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        result.screenshot?.let { content.add(ImageContent(data = it, mimeType = "image/png")) }
        CallToolResult(content = content)
    }

    // 2b. tap
    server.addTool(
        name = McpToolCatalog.tap.name,
        description = McpToolCatalog.tap.description,
        inputSchema = McpToolCatalog.tap.inputSchema!!
    ) { request ->
        val button = request.arguments?.get("button")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: button")), isError = true)
        val count = request.arguments?.get("count")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val pressFrames = request.arguments?.get("press_frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 5
        val gapFrames = request.arguments?.get("gap_frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 15
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val result = toolset.tap(button, count, pressFrames, gapFrames, screenshot)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        result.screenshot?.let { content.add(ImageContent(data = it, mimeType = "image/png")) }
        CallToolResult(content = content)
    }

    // 2c. sequence
    server.addTool(
        name = McpToolCatalog.sequence.name,
        description = McpToolCatalog.sequence.description,
        inputSchema = McpToolCatalog.sequence.inputSchema!!
    ) { request ->
        val stepsArray = request.arguments?.get("steps")?.jsonArray
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: steps")), isError = true)
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val steps = stepsArray.map { step ->
            val obj = step.jsonObject
            val buttons = obj["buttons"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val frames = obj["frames"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            StepEntry(buttons, frames)
        }
        val result = toolset.sequence(steps, screenshot)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        result.screenshot?.let { content.add(ImageContent(data = it, mimeType = "image/png")) }
        CallToolResult(content = content)
    }

    // 3. get_state
    server.addTool(
        name = McpToolCatalog.getState.name,
        description = McpToolCatalog.getState.description
    ) { _ ->
        val result = toolset.getState()
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    // 4. get_screen
    server.addTool(
        name = McpToolCatalog.getScreen.name,
        description = McpToolCatalog.getScreen.description
    ) { _ ->
        val result = toolset.getScreen()
        CallToolResult(content = listOf(ImageContent(data = result.base64, mimeType = "image/png")))
    }

    // 4b. observe
    server.addTool(
        name = McpToolCatalog.observe.name,
        description = McpToolCatalog.observe.description,
        inputSchema = McpToolCatalog.observe.inputSchema!!
    ) { request ->
        val profileId = request.arguments?.get("profile_id")?.jsonPrimitive?.content
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val result = toolset.observe(profileId, screenshot)
        val content = mutableListOf<ContentBlock>(TextContent(json.encodeToString(result)))
        result.screenshot?.base64?.let { content.add(ImageContent(data = it, mimeType = "image/png")) }
        CallToolResult(content = content)
    }

    // 5. apply_profile
    server.addTool(
        name = McpToolCatalog.applyProfile.name,
        description = McpToolCatalog.applyProfile.description,
        inputSchema = McpToolCatalog.applyProfile.inputSchema!!
    ) { request ->
        val id = request.arguments?.get("profile_id")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: profile_id")), isError = true)
        val result = toolset.applyProfile(id)
        if (result.ok) {
            CallToolResult(content = listOf(TextContent("Profile '$id' applied. RAM values will appear in step and get_state responses.")))
        } else {
            CallToolResult(content = listOf(TextContent(result.message)), isError = true)
        }
    }

    // 5b. list_actions
    server.addTool(
        name = McpToolCatalog.listActions.name,
        description = McpToolCatalog.listActions.description,
        inputSchema = McpToolCatalog.listActions.inputSchema!!
    ) { request ->
        val profileId = request.arguments?.get("profile_id")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Missing profile_id")), isError = true
            )
        val actions = toolset.listActions(profileId)
        CallToolResult(content = listOf(TextContent(json.encodeToString(actions))))
    }

    // 5c. execute_action
    server.addTool(
        name = McpToolCatalog.executeAction.name,
        description = McpToolCatalog.executeAction.description,
        inputSchema = McpToolCatalog.executeAction.inputSchema!!
    ) { request ->
        val profileId = request.arguments?.get("profile_id")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Missing profile_id")), isError = true
            )
        val actionId = request.arguments?.get("action_id")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Missing action_id")), isError = true
            )
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val result = toolset.executeAction(profileId, actionId)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        // executeAction doesn't return a screenshot directly; get_screen can be called separately
        CallToolResult(content = content, isError = !result.ok)
    }

    // 6. list_profiles
    server.addTool(
        name = McpToolCatalog.listProfiles.name,
        description = McpToolCatalog.listProfiles.description
    ) { _ ->
        val profiles = toolset.listProfiles()
        CallToolResult(content = listOf(TextContent(json.encodeToString(profiles))))
    }

    // 7. press
    server.addTool(
        name = McpToolCatalog.press.name,
        description = McpToolCatalog.press.description,
        inputSchema = McpToolCatalog.press.inputSchema!!
    ) { request ->
        val buttons = (request.arguments?.get("buttons") as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'buttons' must be an array of button names")), isError = true
            )
        val result = toolset.press(buttons)
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    // 8. release
    server.addTool(
        name = McpToolCatalog.release.name,
        description = McpToolCatalog.release.description,
        inputSchema = McpToolCatalog.release.inputSchema!!
    ) { request ->
        val buttons = (request.arguments?.get("buttons") as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'buttons' must be an array of button names")), isError = true
            )
        val result = toolset.release(buttons)
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    // 9. reset
    server.addTool(
        name = McpToolCatalog.reset.name,
        description = McpToolCatalog.reset.description
    ) { _ ->
        val result = toolset.reset()
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    return server
}

fun runMcpServer(server: Server) {
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )
    kotlinx.coroutines.runBlocking {
        server.createSession(transport)
        val done = kotlinx.coroutines.Job()
        server.onClose { done.complete() }
        done.join()
    }
}

/** In-process server: runs the emulator itself, no separate REST process needed. */
fun createMcpServer(): Server = createMcpServer { LocalEmulatorToolset(EmulatorSession()) }

/**
 * Remote server: drives the emulator hosted by the Compose UI's embedded API.
 *
 * Start the Compose UI first, click "API Server", then launch with --remote.
 */
fun createRemoteMcpServer(baseUrl: String = DEFAULT_REMOTE_URL): Server =
    createMcpServer { EmulatorToolset.remote(baseUrl) }

const val DEFAULT_REMOTE_URL: String = "http://localhost:6502"
