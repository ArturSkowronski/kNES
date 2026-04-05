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

fun createMcpServer(): Server {
    val session = NesEmulatorSession()

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
        description = "Load a NES ROM from the given file path",
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
        val loaded = session.loadRom(path)
        if (loaded) {
            CallToolResult(content = listOf(TextContent("ROM loaded successfully: $path")))
        } else {
            CallToolResult(content = listOf(TextContent("Failed to load ROM: $path")), isError = true)
        }
    }

    // 2. step
    server.addTool(
        name = "step",
        description = "Advance emulation by the given number of frames while holding the specified buttons. Returns frame count and watched RAM values.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("buttons") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Buttons to hold during step: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
                putJsonObject("frames") {
                    put("type", "integer")
                    put("description", "Number of frames to advance (default: 1)")
                }
            },
            required = listOf()
        )
    ) { request ->
        if (!session.romLoaded) {
            return@addTool CallToolResult(content = listOf(TextContent("No ROM loaded. Use load_rom first.")), isError = true)
        }
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val frames = request.arguments?.get("frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        try {
            session.step(buttons, frames)
            val ram = session.getWatchedState()
            val ramStr = if (ram.isEmpty()) "none" else ram.entries.joinToString(", ") { "${it.key}=${it.value}" }
            CallToolResult(content = listOf(TextContent("frame=${session.frameCount} ram={$ramStr}")))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("step failed: ${e.message}")), isError = true)
        }
    }

    // 3. get_state
    server.addTool(
        name = "get_state",
        description = "Get current emulator state: frame count, watched RAM values, CPU registers, and held buttons"
    ) { _ ->
        if (!session.romLoaded) {
            return@addTool CallToolResult(content = listOf(TextContent("No ROM loaded. Use load_rom first.")), isError = true)
        }
        val ram = session.getWatchedState()
        val ramStr = if (ram.isEmpty()) "none" else ram.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val buttons = session.getHeldButtons()
        val cpu = session.nes.cpu
        val state = buildString {
            appendLine("frame=${session.frameCount}")
            appendLine("ram={$ramStr}")
            appendLine("buttons=${buttons}")
            appendLine("cpu: PC=${cpu.REG_PC_NEW} A=${cpu.REG_ACC_NEW} X=${cpu.REG_X_NEW} Y=${cpu.REG_Y_NEW} SP=${cpu.REG_SP}")
        }
        CallToolResult(content = listOf(TextContent(state)))
    }

    // 4. get_screen
    server.addTool(
        name = "get_screen",
        description = "Capture a screenshot of the current NES frame as a base64-encoded PNG image"
    ) { _ ->
        if (!session.romLoaded) {
            return@addTool CallToolResult(content = listOf(TextContent("No ROM loaded. Use load_rom first.")), isError = true)
        }
        val base64 = session.getScreenBase64()
        CallToolResult(content = listOf(ImageContent(data = base64, mimeType = "image/png")))
    }

    // 5. apply_profile
    server.addTool(
        name = "apply_profile",
        description = "Apply a game profile to enable RAM watching for known addresses (e.g. score, lives, level)",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("profile_id") {
                    put("type", "string")
                    put("description", "Profile ID to apply (use list_profiles to see available ones)")
                }
            },
            required = listOf("profile_id")
        )
    ) { request ->
        val id = request.arguments?.get("profile_id")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required parameter: profile_id")), isError = true)
        val applied = session.applyProfile(id)
        if (applied) {
            val watched = session.getWatchedState()
            CallToolResult(content = listOf(TextContent("Profile '$id' applied. Watching ${watched.size} addresses: ${watched.keys.joinToString(", ")}")))
        } else {
            CallToolResult(content = listOf(TextContent("Profile not found: $id")), isError = true)
        }
    }

    // 6. list_profiles
    server.addTool(
        name = "list_profiles",
        description = "List all available game profiles for RAM watching"
    ) { _ ->
        val profiles = knes.debug.GameProfile.list()
        if (profiles.isEmpty()) {
            CallToolResult(content = listOf(TextContent("No profiles available")))
        } else {
            val list = profiles.joinToString("\n") { p -> "- ${p.id}: ${p.name} (${p.addresses.size} addresses) - ${p.description}" }
            CallToolResult(content = listOf(TextContent("Available profiles:\n$list")))
        }
    }

    // 7. press
    server.addTool(
        name = "press",
        description = "Press and hold one or more buttons",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("buttons") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Buttons to press: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
            },
            required = listOf("buttons")
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required parameter: buttons")), isError = true)
        try {
            for (b in buttons) session.pressButton(b)
            CallToolResult(content = listOf(TextContent("Holding: ${session.getHeldButtons()}")))
        } catch (e: IllegalArgumentException) {
            CallToolResult(content = listOf(TextContent(e.message ?: "Unknown button")), isError = true)
        }
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
                    put("description", "Buttons to release: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                }
            },
            required = listOf("buttons")
        )
    ) { request ->
        val buttons = request.arguments?.get("buttons")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required parameter: buttons")), isError = true)
        try {
            for (b in buttons) session.releaseButton(b)
            CallToolResult(content = listOf(TextContent("Holding: ${session.getHeldButtons()}")))
        } catch (e: IllegalArgumentException) {
            CallToolResult(content = listOf(TextContent(e.message ?: "Unknown button")), isError = true)
        }
    }

    // 9. reset
    server.addTool(
        name = "reset",
        description = "Reset the NES emulator to its initial state"
    ) { _ ->
        session.reset()
        CallToolResult(content = listOf(TextContent("Emulator reset. frame=${session.frameCount}")))
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
