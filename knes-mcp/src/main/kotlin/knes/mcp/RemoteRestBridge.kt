package knes.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
        description = "Advance emulation by N frames while holding specified buttons. Returns frame count, watched RAM values, and optionally a screenshot.",
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
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "If true, include a screenshot of the final frame in the response (default: false)")
                }
            },
            required = listOf()
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val frames = request.arguments?.get("frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val buttonsJson = buttons.joinToString(",") { "\"$it\"" }
        val resp = api.postJson("/step", """{"buttons":[$buttonsJson],"frames":$frames,"screenshot":$screenshot}""")
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
        name = "tap",
        description = "Press a button N times with configurable timing. Equivalent to repeated step(button, press_frames) + step([], gap_frames) cycles. Returns frame count, RAM, and optionally a screenshot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("button") {
                    put("type", "string")
                    put("description", "Button to press: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Number of times to press (default: 1)")
                }
                putJsonObject("press_frames") {
                    put("type", "integer")
                    put("description", "Frames to hold each press (default: 5)")
                }
                putJsonObject("gap_frames") {
                    put("type", "integer")
                    put("description", "Frames to wait between presses (default: 15)")
                }
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "If true, include a screenshot after all presses complete (default: false)")
                }
            },
            required = listOf("button")
        )
    ) { request ->
        val button = request.arguments?.get("button")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: button")), isError = true)
        val count = request.arguments?.get("count")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val pressFrames = request.arguments?.get("press_frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 5
        val gapFrames = request.arguments?.get("gap_frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 15
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val resp = api.postJson("/tap", """{"button":"$button","count":$count,"pressFrames":$pressFrames,"gapFrames":$gapFrames,"screenshot":$screenshot}""")
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
        name = "sequence",
        description = "Execute a sequence of button inputs in one call. Each step holds specified buttons for N frames. Returns frame count, RAM, and optionally a screenshot after all steps complete.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("steps") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("buttons") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                            putJsonObject("frames") {
                                put("type", "integer")
                            }
                        }
                    }
                    put("description", "Array of {buttons, frames} steps to execute in order")
                }
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "If true, include a screenshot after all steps complete (default: false)")
                }
            },
            required = listOf("steps")
        )
    ) { request ->
        val stepsArray = request.arguments?.get("steps")?.jsonArray
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing: steps")), isError = true)
        val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val stepsJson = stepsArray.joinToString(",") { step ->
            val obj = step.jsonObject
            val buttons = obj["buttons"]?.jsonArray?.joinToString(",") { "\"${it.jsonPrimitive.content}\"" } ?: ""
            val frames = obj["frames"]?.jsonPrimitive?.content ?: "1"
            """{"buttons":[$buttons],"frames":$frames}"""
        }
        val resp = api.postJson("/step", """{"sequence":[$stepsJson],"screenshot":$screenshot}""")
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

    // 5b. list_actions
    server.addTool(
        name = "list_actions",
        description = "List available game actions for a profile. Actions are game-specific automation scripts that play like a real NES player — they read the screen and press buttons.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("profile_id") {
                    put("type", "string")
                    put("description", "Profile ID (e.g. 'ff1')")
                }
            },
            required = listOf("profile_id")
        )
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
        name = "execute_action",
        description = "Execute a game action. Actions play like a real NES player: they read RAM state and press buttons. No memory writes, no cheats. Example: execute_action('ff1', 'battle_fight_all') auto-fights an FF1 battle.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("profile_id") {
                    put("type", "string")
                    put("description", "Profile ID (e.g. 'ff1')")
                }
                putJsonObject("action_id") {
                    put("type", "string")
                    put("description", "Action ID (e.g. 'battle_fight_all')")
                }
                putJsonObject("screenshot") {
                    put("type", "boolean")
                    put("description", "Include screenshot in result (default: true)")
                }
            },
            required = listOf("profile_id", "action_id")
        )
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
            """{"screenshot":$screenshot}"""
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
