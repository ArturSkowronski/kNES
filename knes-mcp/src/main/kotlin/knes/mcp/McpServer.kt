package knes.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP server that bridges to the kNES REST API.
 *
 * Connects to the Compose UI's embedded API server (localhost:6502) so the LLM
 * can control the emulator while the user watches on screen.
 *
 * Start the Compose UI first, click "API Server", then launch this MCP server.
 */
fun createMcpServer(): Server {
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
        name = "load_rom",
        description = "Load a NES ROM from the given file path. Requires the Compose UI with embedded API server running on port 6502.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute path to the .nes ROM file")
                }
            },
            required = listOf("path")
        )
    ) { request ->
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required parameter: path")), isError = true)
        if (!api.isAvailable()) {
            return@addTool CallToolResult(content = listOf(TextContent("Cannot connect to kNES API on port 6502. Start the Compose UI and click 'API Server' first.")), isError = true)
        }
        val resp = api.postJson("/rom", """{"path":"$path"}""")
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent("ROM loaded: $path")))
        } else {
            CallToolResult(content = listOf(TextContent("Failed to load ROM: ${resp.body}")), isError = true)
        }
    }

    // 2. step
    server.addTool(
        name = "step",
        description = "Advance emulation by N frames while holding specified buttons. Returns frame count and watched RAM values. The game runs visually in the Compose UI while stepping.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("buttons") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Buttons to hold: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT. Empty array = no buttons.")
                }
                putJsonObject("frames") {
                    put("type", "integer")
                    put("description", "Number of frames to advance (default: 1, 60 frames = 1 second)")
                }
            },
            required = listOf()
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val frames = request.arguments?.get("frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val buttonsJson = buttons.joinToString(",") { "\"$it\"" }
        val resp = api.postJson("/step", """{"buttons":[$buttonsJson],"frames":$frames}""")
        if (resp.ok) {
            CallToolResult(content = listOf(TextContent(resp.body)))
        } else {
            CallToolResult(content = listOf(TextContent("step failed: ${resp.body}")), isError = true)
        }
    }

    // 3. get_state
    server.addTool(
        name = "get_state",
        description = "Get current emulator state: frame count, watched RAM values, CPU registers, and held buttons"
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
        name = "get_screen",
        description = "Capture a screenshot of the current NES frame as a base64-encoded PNG image"
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
        name = "apply_profile",
        description = "Apply a game profile (e.g. 'smb' for Super Mario Bros, 'ff1' for Final Fantasy) to enable RAM watching for game-specific variables like HP, gold, position",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("profile_id") {
                    put("type", "string")
                    put("description", "Profile ID: 'smb' (Super Mario Bros) or 'ff1' (Final Fantasy)")
                }
            },
            required = listOf("profile_id")
        )
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

    // 6. list_profiles
    server.addTool(
        name = "list_profiles",
        description = "List all available game profiles for RAM watching"
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
        name = "press",
        description = "Press and hold one or more buttons (they stay held until released)",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("buttons") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Buttons: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
            },
            required = listOf("buttons")
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: buttons")), isError = true)
        val json = buttons.joinToString(",") { "\"$it\"" }
        val resp = api.postJson("/press", """{"buttons":[$json]}""")
        CallToolResult(content = listOf(TextContent(resp.body)))
    }

    // 8. release
    server.addTool(
        name = "release",
        description = "Release one or more held buttons",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("buttons") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Buttons: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
            },
            required = listOf("buttons")
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: buttons")), isError = true)
        val json = buttons.joinToString(",") { "\"$it\"" }
        val resp = api.postJson("/release", """{"buttons":[$json]}""")
        CallToolResult(content = listOf(TextContent(resp.body)))
    }

    // 9. reset
    server.addTool(
        name = "reset",
        description = "Reset the NES emulator to its initial state"
    ) { _ ->
        val resp = api.postJson("/reset", "")
        CallToolResult(content = listOf(TextContent(resp.body)))
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
    }
}
