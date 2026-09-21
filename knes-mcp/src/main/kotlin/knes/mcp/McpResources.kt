package knes.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import knes.agent.tools.EmulatorToolset
import knes.debug.GameProfile
import knes.debug.ProfileSemantics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Read-only data the model can pull without spending a tool call.
 *
 * Tools are for acting; a tool that only answers "what is true right now" makes the
 * model pay a round trip to re-read something that did not change. Watched RAM
 * definitions and profile semantics in particular are static for a whole session.
 *
 * Registration must not touch the backend — see the lazy note in [createMcpServer].
 * Every read handler may, because by then the caller has asked for it. [backend] must
 * hand back the *same* instance each call; a provider that constructs one would spin
 * up a fresh emulator per read.
 */
object McpResources {
    const val STATE_URI = "knes://emulator/state"
    const val PROFILES_URI = "knes://emulator/profiles"
    const val WATCHED_RAM_URI = "knes://profiles/watched-ram"
    const val SEMANTICS_URI = "knes://profiles/semantics"

    val uris: List<String> = listOf(STATE_URI, PROFILES_URI, WATCHED_RAM_URI, SEMANTICS_URI)

    fun register(server: Server, backend: () -> EmulatorToolset, json: Json) {
        server.addResource(
            uri = STATE_URI,
            name = "Emulator state",
            description = "Current frame, watched RAM, CPU registers and held buttons.",
            mimeType = "application/json",
        ) { request ->
            request.text(json.encodeToString(backend().getState()))
        }

        server.addResource(
            uri = PROFILES_URI,
            name = "Available game profiles",
            description = "Profiles the emulator backend can apply, with id, name and description.",
            mimeType = "application/json",
        ) { request ->
            request.text(json.encodeToString(backend().listProfiles()))
        }

        server.addResource(
            uri = WATCHED_RAM_URI,
            name = "Watched RAM definitions",
            description =
                "Per profile, the named RAM addresses and what they mean. Addresses marked " +
                    "hidden carry information a human player could not see on screen.",
            mimeType = "application/json",
        ) { request ->
            request.text(json.encodeToString(watchedRam()))
        }

        server.addResource(
            uri = SEMANTICS_URI,
            name = "Profile semantics",
            description =
                "Per profile, how watched RAM is interpreted: phase rules, position field " +
                    "mappings, landmark anchors and game signals.",
            mimeType = "application/json",
        ) { request ->
            request.text(json.encodeToString(semantics(json)))
        }
    }

    /** Address maps are plain data classes, so they are rendered rather than serialized. */
    private fun watchedRam(): JsonObject = buildJsonObject {
        for (profile in GameProfile.list().sortedBy { it.id }) {
            putJsonObject(profile.id) {
                put("name", profile.name)
                putJsonObject("addresses") {
                    for ((field, entry) in profile.addresses.entries.sortedBy { it.key }) {
                        putJsonObject(field) {
                            put("address", "0x%04X".format(entry.address))
                            put("description", entry.description)
                            put("hidden", entry.hidden)
                        }
                    }
                }
            }
        }
    }

    private fun semantics(json: Json): JsonObject = buildJsonObject {
        for (profile in GameProfile.list().sortedBy { it.id }) {
            val semantics = ProfileSemantics.get(profile.id) ?: continue
            put(profile.id, json.encodeToJsonElement(semantics))
        }
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest.text(
        body: String,
    ): ReadResourceResult = ReadResourceResult(
        contents = listOf(TextResourceContents(text = body, uri = uri, mimeType = "application/json")),
    )
}
