package knes.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema? = null
)

object McpToolCatalog {
    val loadRom = McpToolDefinition(
        name = "load_rom",
        description = "Load a NES ROM from the given file path. Requires the Compose UI with embedded API server running on port 6502.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("path", "Absolute path to the .nes ROM file")
            },
            required = listOf("path")
        )
    )

    val step = McpToolDefinition(
        name = "step",
        description = "Advance emulation by N frames while holding specified buttons. Returns frame count, watched RAM values, and optionally a screenshot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringArrayProperty(
                    "buttons",
                    "Buttons to hold: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT. Empty array = no buttons."
                )
                integerProperty("frames", "Number of frames to advance (default: 1, 60 frames = 1 second)")
                booleanProperty(
                    "screenshot",
                    "If true, include a screenshot of the final frame in the response (default: false)"
                )
            },
            required = emptyList()
        )
    )

    val tap = McpToolDefinition(
        name = "tap",
        description = "Press a button N times with configurable timing. Equivalent to repeated step(button, press_frames) + step([], gap_frames) cycles. Returns frame count, RAM, and optionally a screenshot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("button", "Button to press: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
                integerProperty("count", "Number of times to press (default: 1)")
                integerProperty("press_frames", "Frames to hold each press (default: 5)")
                integerProperty("gap_frames", "Frames to wait between presses (default: 15)")
                booleanProperty(
                    "screenshot",
                    "If true, include a screenshot after all presses complete (default: false)"
                )
            },
            required = listOf("button")
        )
    )

    val sequence = McpToolDefinition(
        name = "sequence",
        description = "Execute a sequence of button inputs in one call. Each step holds specified buttons for N frames. Returns frame count, RAM, and optionally a screenshot after all steps complete.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("steps") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            stringArrayProperty("buttons")
                            integerProperty("frames")
                        }
                    }
                    put("description", "Array of {buttons, frames} steps to execute in order")
                }
                booleanProperty(
                    "screenshot",
                    "If true, include a screenshot after all steps complete (default: false)"
                )
            },
            required = listOf("steps")
        )
    )

    val getState = McpToolDefinition(
        name = "get_state",
        description = "Get current emulator state: frame count, watched RAM values, CPU registers, and held buttons"
    )

    val getScreen = McpToolDefinition(
        name = "get_screen",
        description = "Capture a screenshot of the current NES frame as a base64-encoded PNG image"
    )

    val observe = McpToolDefinition(
        name = "observe",
        description = "Get an agent-oriented semantic observation: frame, phase, position, location hint, watched RAM, CPU registers, held buttons, and optionally a screenshot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("profile_id", "Optional game profile ID used for semantic location hints, e.g. 'ff1'")
                booleanProperty("screenshot", "If true, include a screenshot in the observation and as an image content block")
            },
            required = emptyList()
        )
    )

    val applyProfile = McpToolDefinition(
        name = "apply_profile",
        description = "Apply a game profile (e.g. 'smb' for Super Mario Bros, 'ff1' for Final Fantasy) to enable RAM watching for game-specific variables like HP, gold, position",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("profile_id", "Profile ID: 'smb' (Super Mario Bros) or 'ff1' (Final Fantasy)")
            },
            required = listOf("profile_id")
        )
    )

    val listActions = McpToolDefinition(
        name = "list_actions",
        description = "List available game actions for a profile. Actions are game-specific automation scripts that play like a real NES player - they read the screen and press buttons.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("profile_id", "Profile ID (e.g. 'ff1')")
            },
            required = listOf("profile_id")
        )
    )

    val executeAction = McpToolDefinition(
        name = "execute_action",
        description = "Execute a game action. Actions play like a real NES player: they read RAM state and press buttons. No memory writes, no cheats. Example: execute_action('ff1', 'battle_fight_all') auto-fights an FF1 battle.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("profile_id", "Profile ID (e.g. 'ff1')")
                stringProperty("action_id", "Action ID (e.g. 'battle_fight_all')")
                booleanProperty("screenshot", "Include screenshot in result (default: true)")
            },
            required = listOf("profile_id", "action_id")
        )
    )

    val listProfiles = McpToolDefinition(
        name = "list_profiles",
        description = "List all available game profiles for RAM watching"
    )

    val press = McpToolDefinition(
        name = "press",
        description = "Press and hold one or more buttons (they stay held until released)",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringArrayProperty("buttons", "Buttons: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
            },
            required = listOf("buttons")
        )
    )

    val release = McpToolDefinition(
        name = "release",
        description = "Release one or more held buttons",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringArrayProperty("buttons", "Buttons: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT")
            },
            required = listOf("buttons")
        )
    )

    val reset = McpToolDefinition(
        name = "reset",
        description = "Reset the NES emulator to its initial state"
    )

    val all = listOf(
        loadRom,
        step,
        tap,
        sequence,
        getState,
        getScreen,
        observe,
        applyProfile,
        listActions,
        executeAction,
        listProfiles,
        press,
        release,
        reset
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String? = null) {
    putJsonObject(name) {
        put("type", "string")
        description?.let { put("description", it) }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(name: String, description: String? = null) {
    putJsonObject(name) {
        put("type", "integer")
        description?.let { put("description", it) }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.booleanProperty(name: String, description: String? = null) {
    putJsonObject(name) {
        put("type", "boolean")
        description?.let { put("description", it) }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringArrayProperty(name: String, description: String? = null) {
    putJsonObject(name) {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        description?.let { put("description", it) }
    }
}
