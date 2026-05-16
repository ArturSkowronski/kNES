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
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import knes.agent.tools.LocalEmulatorToolset
import knes.agent.tools.results.StepEntry
import knes.api.EmulatorSession
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
        val result = toolset.loadRom(path)
        if (result.ok) {
            CallToolResult(content = listOf(TextContent(result.message)))
        } else {
            CallToolResult(content = listOf(TextContent(result.message)), isError = true)
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
        val result = toolset.step(buttons, frames, screenshot)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        result.screenshot?.let { content.add(ImageContent(data = it, mimeType = "image/png")) }
        CallToolResult(content = content)
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
        val result = toolset.tap(button, count, pressFrames, gapFrames, screenshot)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        result.screenshot?.let { content.add(ImageContent(data = it, mimeType = "image/png")) }
        CallToolResult(content = content)
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
        name = "get_state",
        description = "Get current emulator state: frame count, watched RAM values, CPU registers, and held buttons"
    ) { _ ->
        val result = toolset.getState()
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    // 4. get_screen
    server.addTool(
        name = "get_screen",
        description = "Capture a screenshot of the current NES frame as a base64-encoded PNG image"
    ) { _ ->
        val result = toolset.getScreen()
        CallToolResult(content = listOf(ImageContent(data = result.base64, mimeType = "image/png")))
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
        val result = toolset.applyProfile(id)
        if (result.ok) {
            CallToolResult(content = listOf(TextContent("Profile '$id' applied. RAM values will appear in step and get_state responses.")))
        } else {
            CallToolResult(content = listOf(TextContent(result.message)), isError = true)
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
        val actions = toolset.listActions(profileId)
        CallToolResult(content = listOf(TextContent(json.encodeToString(actions))))
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
        val result = toolset.executeAction(profileId, actionId)
        val text = json.encodeToString(result)
        val content = mutableListOf<ContentBlock>(TextContent(text))
        // executeAction doesn't return a screenshot directly; get_screen can be called separately
        CallToolResult(content = content, isError = !result.ok)
    }

    // 6. list_profiles
    server.addTool(
        name = "list_profiles",
        description = "List all available game profiles for RAM watching"
    ) { _ ->
        val profiles = toolset.listProfiles()
        CallToolResult(content = listOf(TextContent(json.encodeToString(profiles))))
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
        val result = toolset.press(buttons)
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
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
        val result = toolset.release(buttons)
        CallToolResult(content = listOf(TextContent(json.encodeToString(result))))
    }

    // 9. reset
    server.addTool(
        name = "reset",
        description = "Reset the NES emulator to its initial state"
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
