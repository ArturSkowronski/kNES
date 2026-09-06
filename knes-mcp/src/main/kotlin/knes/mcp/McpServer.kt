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
import knes.agent.tools.LocalEmulatorToolset
import knes.agent.tools.results.StepEntry
import knes.api.EmulatorSession
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-process MCP server that delegates to [EmulatorToolset].
 *
 * Runs the emulator directly — no separate REST process required.
 * Use [createRemoteMcpServer] (--remote flag) for the legacy REST-bridge mode
 * where the Compose UI hosts the emulator on port 6502.
 */
fun createMcpServer(): Server {
    val session = EmulatorSession()
    val toolset = LocalEmulatorToolset(session)

    val server = Server(
        serverInfo = Implementation(
            name = "knes-mcp",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    val json = Json { encodeDefaults = true }

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
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: buttons")), isError = true)
        val result = toolset.press(buttons)
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    // 8. release
    server.addTool(
        name = McpToolCatalog.release.name,
        description = McpToolCatalog.release.description,
        inputSchema = McpToolCatalog.release.inputSchema!!
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: buttons")), isError = true)
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
