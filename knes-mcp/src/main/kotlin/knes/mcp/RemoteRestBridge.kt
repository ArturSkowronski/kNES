package knes.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Legacy REST-bridge MCP server.
 *
 * Connects to the Compose UI's embedded API server (localhost:6502) so the LLM
 * can control the emulator while the user watches on screen.
 *
 * Start the Compose UI first, click "API Server", then launch with --remote.
 */
fun createRemoteMcpServer(): Server {
    val api = RestApiClient()

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

    // 1. load_rom
    server.addTool(
        name = McpToolCatalog.loadRom.name,
        description = McpToolCatalog.loadRom.description,
        inputSchema = McpToolCatalog.loadRom.inputSchema!!
    ) { request ->
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required parameter: path")), isError = true)
        if (!api.isAvailable()) {
            return@addTool CallToolResult(content = listOf(TextContent("Cannot connect to kNES API on port 6502. Start the Compose UI and click 'API Server' first.")), isError = true)
        }
        val resp = api.postJson(
            "/rom",
            buildJsonObject {
                put("path", path)
            }
        )
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent("ROM loaded: $path")))
        } else {
            CallToolResult(content = listOf(TextContent("Failed to load ROM: ${resp.body}")), isError = true)
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
        val resp = api.postJson(
            "/step",
            buildJsonObject {
                putJsonArray("buttons") {
                    buttons.forEach { add(it) }
                }
                put("frames", frames)
                put("screenshot", screenshot)
            }
        )
        if (resp.ok) {
            val content = mutableListOf<ContentBlock>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(content = listOf(TextContent("step failed: ${resp.body}")), isError = true)
        }
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
        val resp = api.postJson(
            "/tap",
            buildJsonObject {
                put("button", button)
                put("count", count)
                put("pressFrames", pressFrames)
                put("gapFrames", gapFrames)
                put("screenshot", screenshot)
            }
        )
        if (resp.ok) {
            val content = mutableListOf<ContentBlock>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(content = listOf(TextContent("tap failed: ${resp.body}")), isError = true)
        }
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

        val resp = api.postJson(
            "/step",
            buildJsonObject {
                putJsonArray("sequence") {
                    stepsArray.forEach { step ->
                        val obj = step.jsonObject
                        val buttons = obj["buttons"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                        val frames = obj["frames"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                        addJsonObject {
                            putJsonArray("buttons") {
                                buttons.forEach { add(it) }
                            }
                            put("frames", frames)
                        }
                    }
                }
                put("screenshot", screenshot)
            }
        )
        if (resp.ok) {
            val content = mutableListOf<ContentBlock>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(content = listOf(TextContent("sequence failed: ${resp.body}")), isError = true)
        }
    }

    // 3. get_state
    server.addTool(
        name = McpToolCatalog.getState.name,
        description = McpToolCatalog.getState.description
    ) { _ ->
        val resp = api.get("/state")
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent(resp.body)))
        } else {
            CallToolResult(content = listOf(TextContent("get_state failed: ${resp.body}")), isError = true)
        }
    }

    // 4. get_screen
    server.addTool(
        name = McpToolCatalog.getScreen.name,
        description = McpToolCatalog.getScreen.description
    ) { _ ->
        val resp = api.get("/screen/base64")
        if (resp.ok) {
            // Extract base64 image from JSON response {"frame":N,"image":"..."}
            val imageMatch = Regex(""""image"\s*:\s*"([^"]+)"""").find(resp.body)
            if (imageMatch != null) {
                CallToolResult(content = listOf(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png")))
            } else {
                CallToolResult(content = listOf(TextContent(resp.body)))
            }
        } else {
            CallToolResult(content = listOf(TextContent("get_screen failed: ${resp.body}")), isError = true)
        }
    }

    // 5. apply_profile
    server.addTool(
        name = McpToolCatalog.applyProfile.name,
        description = McpToolCatalog.applyProfile.description,
        inputSchema = McpToolCatalog.applyProfile.inputSchema!!
    ) { request ->
        val id = request.arguments?.get("profile_id")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: profile_id")), isError = true)
        val resp = api.postJson("/profiles/$id/apply", "")
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent("Profile '$id' applied. RAM values will appear in step and get_state responses.")))
        } else {
            CallToolResult(content = listOf(TextContent("Failed to apply profile: ${resp.body}")), isError = true)
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

        val resp = api.get("/profiles/$profileId/actions")
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent(resp.body)))
        } else {
            CallToolResult(
                content = listOf(TextContent("list_actions failed: ${resp.body}")), isError = true
            )
        }
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

        val resp = api.postJson(
            "/profiles/$profileId/actions/$actionId",
            buildJsonObject {
                put("screenshot", screenshot)
            }
        )
        if (resp.ok) {
            val content = mutableListOf<ContentBlock>(TextContent(resp.body))
            if (screenshot) {
                val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
                if (imageMatch != null) {
                    content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
                }
            }
            CallToolResult(content = content)
        } else {
            CallToolResult(
                content = listOf(TextContent("execute_action failed: ${resp.body}")), isError = true
            )
        }
    }

    // 6. list_profiles
    server.addTool(
        name = McpToolCatalog.listProfiles.name,
        description = McpToolCatalog.listProfiles.description
    ) { _ ->
        val resp = api.get("/profiles")
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent(resp.body)))
        } else {
            CallToolResult(content = listOf(TextContent("list_profiles failed: ${resp.body}")), isError = true)
        }
    }

    // 7. press
    server.addTool(
        name = McpToolCatalog.press.name,
        description = McpToolCatalog.press.description,
        inputSchema = McpToolCatalog.press.inputSchema!!
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: buttons")), isError = true)
        val resp = api.postJson(
            "/press",
            buildJsonObject {
                putJsonArray("buttons") {
                    buttons.forEach { add(it) }
                }
            }
        )
        CallToolResult(content = listOf(TextContent(resp.body)))
    }

    // 8. release
    server.addTool(
        name = McpToolCatalog.release.name,
        description = McpToolCatalog.release.description,
        inputSchema = McpToolCatalog.release.inputSchema!!
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: buttons")), isError = true)
        val resp = api.postJson(
            "/release",
            buildJsonObject {
                putJsonArray("buttons") {
                    buttons.forEach { add(it) }
                }
            }
        )
        CallToolResult(content = listOf(TextContent(resp.body)))
    }

    // 9. reset
    server.addTool(
        name = McpToolCatalog.reset.name,
        description = McpToolCatalog.reset.description
    ) { _ ->
        val resp = api.postJson("/reset", "")
        CallToolResult(content = listOf(TextContent(resp.body)))
    }

    return server
}
