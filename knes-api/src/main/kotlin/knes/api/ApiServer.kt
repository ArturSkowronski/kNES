package knes.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import knes.agent.tools.EmulatorToolset
import knes.emulator.input.InputHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class RomRequest(val path: String)
// StepRequest is defined in knes-emulator-session module (knes.api package)
@Serializable data class StepSequence(val sequence: List<StepRequest>, val screenshot: Boolean = false)
@Serializable data class TapRequest(val button: String, val count: Int = 1, val pressFrames: Int = 5, val gapFrames: Int = 15, val screenshot: Boolean = false)
@Serializable data class ButtonsRequest(val buttons: List<String>)
@Serializable data class WatchRequest(val addresses: Map<String, String>)
@Serializable data class StatusResponse(val status: String, val romLoaded: Boolean = false, val frames: Int = 0)
@Serializable data class StepResponse(val frame: Int, val ram: Map<String, Int> = emptyMap(), val screenshot: String? = null)
@Serializable data class ScreenBase64Response(val frame: Int, val image: String)
@Serializable data class StateResponse(val frame: Int, val ram: Map<String, Int>, val buttons: List<String>, val cpu: CpuState)
@Serializable data class CpuState(val pc: Int, val a: Int, val x: Int, val y: Int, val sp: Int)
@Serializable data class Fm2Response(val framesExecuted: Int, val frame: Int)
@Serializable data class ButtonStateResponse(val status: String, val held: List<String>)

@Serializable
data class ActionInfo(
    val id: String,
    val description: String,
    val canExecute: Boolean
)

@Serializable
data class ActionListResponse(
    val profileId: String,
    val actions: List<ActionInfo>
)

@Serializable
data class ActionExecuteRequest(
    val screenshot: Boolean = true
)

@Serializable
data class ActionExecuteResponse(
    val success: Boolean,
    val message: String,
    val state: Map<String, Int> = emptyMap(),
    val screenshot: String? = null
)

fun Application.configureRoutes(session: EmulatorSession) {
    val toolset = EmulatorToolset(session)

    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }

    routing {
        // Health — not delegated (reads session fields directly)
        get("/health") {
            call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
        }

        // ROM load — delegated; shared-mode guard preserved in route
        post("/rom") {
            if (session.shared) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("shared mode: use UI to load ROM"))
                return@post
            }
            val req = call.receive<RomRequest>()
            val result = toolset.loadRom(req.path)
            if (result.ok) {
                call.respond(StatusResponse("loaded", romLoaded = true))
            } else {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("failed"))
            }
        }

        // Reset — delegated; wrap StatusResult → StatusResponse to preserve "status" field
        post("/reset") {
            toolset.reset()
            call.respond(StatusResponse("reset", session.romLoaded, session.frameCount))
        }

        // Step — delegated: toolset.sequence / toolset.step handle both standalone and shared mode.
        post("/step") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val text = call.receiveText()
            val parsed: Pair<List<knes.agent.tools.results.StepEntry>, Boolean>
            try {
                parsed = try {
                    val seq = Json.decodeFromString<StepSequence>(text)
                    Pair(seq.sequence.map { knes.agent.tools.results.StepEntry(it.buttons, it.frames) }, seq.screenshot)
                } catch (e: Exception) {
                    val req = Json.decodeFromString<StepRequest>(text)
                    Pair(listOf(knes.agent.tools.results.StepEntry(req.buttons, req.frames)), req.screenshot)
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid request: ${e.message}"))
                return@post
            }
            val (entries, wantScreenshot) = parsed
            try {
                val result = if (entries.size == 1) {
                    toolset.step(entries[0].buttons, entries[0].frames, wantScreenshot)
                } else {
                    toolset.sequence(entries, wantScreenshot)
                }
                call.respond(StepResponse(result.frame, result.ram, result.screenshot))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, StatusResponse("step failed: ${e.message}"))
            }
        }

        // Tap — delegated: toolset.tap handles both standalone and shared mode.
        post("/tap") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val req: TapRequest
            try {
                req = call.receive<TapRequest>()
                session.controller.resolveButton(req.button) // validate button name
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid request: ${e.message}"))
                return@post
            }
            try {
                val result = toolset.tap(req.button, req.count, req.pressFrames, req.gapFrames, req.screenshot)
                call.respond(StepResponse(result.frame, result.ram, result.screenshot))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, StatusResponse("tap failed: ${e.message}"))
            }
        }

        // Screen (binary PNG) — delegated; base64-decode toolset result
        get("/screen") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@get
            }
            val png = java.util.Base64.getDecoder().decode(toolset.getScreen().base64)
            call.respondBytes(png, ContentType.Image.PNG)
        }

        // Screen (base64) — delegated; wrap into legacy ScreenBase64Response (field: "image")
        get("/screen/base64") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@get
            }
            val screen = toolset.getScreen()
            call.respond(ScreenBase64Response(session.frameCount, screen.base64))
        }

        // State — delegated; StateSnapshot serializes ram/cpu/heldButtons (compatible with tests)
        get("/state") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@get
            }
            call.respond(toolset.getState())
        }

        // Watch — NOT delegated: no toolset method for setting watched addresses
        post("/watch") {
            val req = call.receive<WatchRequest>()
            val addresses = req.addresses.mapValues { (_, v) ->
                v.removePrefix("0x").removePrefix("0X").toInt(16)
            }
            session.setWatchedAddresses(addresses)
            call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
        }

        // Profiles list — delegated; ProfileSummary serializes id/name/description
        get("/profiles") {
            call.respond(toolset.listProfiles())
        }

        // Profile detail — NOT delegated: toolset has no getProfile(); keep using debug API
        get("/profiles/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, StatusResponse("missing profile id")
            )
            val profile = knes.debug.GameProfile.get(id) ?: return@get call.respond(
                HttpStatusCode.NotFound, StatusResponse("profile not found: $id")
            )
            call.respond(ApiGameProfile.fromDebugProfile(profile))
        }

        // Apply profile — delegated; wrap StatusResult → StatusResponse for 404 case
        post("/profiles/{id}/apply") {
            val id = call.parameters["id"] ?: return@post call.respond(
                HttpStatusCode.BadRequest, StatusResponse("missing profile id")
            )
            val result = toolset.applyProfile(id)
            if (!result.ok) {
                call.respond(HttpStatusCode.NotFound, StatusResponse("profile not found: $id"))
            } else {
                call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
            }
        }

        // List actions — delegated; ActionDescriptor has id/profileId/description
        get("/profiles/{id}/actions") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, StatusResponse("missing profile id"))

            val state = if (session.romLoaded) session.getWatchedState() else emptyMap()
            val actions = toolset.listActions(id)

            // canExecute requires loading the action; resolve from GameAction directly
            knes.debug.actions.ActionRegistry.ensureLoaded(id)
            call.respond(ActionListResponse(
                profileId = id,
                actions = actions.map {
                    val action = knes.debug.GameAction.get(id, it.id)
                    ActionInfo(it.id, it.description, action?.canExecute(state) ?: false)
                }
            ))
        }

        // Execute action — delegated; wrap ActionToolResult → ActionExecuteResponse
        post("/profiles/{id}/actions/{actionId}") {
            val profileId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("missing profile id"))
            val actionId = call.parameters["actionId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("missing action id"))

            if (!session.romLoaded) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
            }

            knes.debug.actions.ActionRegistry.ensureLoaded(profileId)
            val action = knes.debug.GameAction.get(profileId, actionId)
                ?: return@post call.respond(HttpStatusCode.NotFound, StatusResponse("action '$actionId' not found for profile '$profileId'"))

            val state = session.getWatchedState()
            if (!action.canExecute(state)) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    StatusResponse("action '$actionId' cannot execute in current state"))
            }

            val result = toolset.executeAction(profileId, actionId)
            call.respond(ActionExecuteResponse(
                success = result.ok,
                message = result.message,
                state = result.data.mapValues { it.value.toIntOrNull() ?: 0 },
                screenshot = null
            ))
        }

        // Register profile — NOT delegated: toolset has no registerProfile()
        post("/profiles") {
            val apiProfile = call.receive<ApiGameProfile>()
            knes.debug.GameProfile.register(apiProfile.toDebugProfile())
            call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
        }

        // Press — NOT delegated: toolset.press() returns StatusResult; tests check "held" field
        post("/press") {
            val req = call.receive<ButtonsRequest>()
            for (name in req.buttons) {
                session.controller.pressButton(session.controller.resolveButton(name))
            }
            call.respond(ButtonStateResponse("ok", session.controller.getHeldButtons()))
        }

        // Release — NOT delegated: same "held" field concern as /press
        post("/release") {
            val req = call.receive<ButtonsRequest>()
            for (name in req.buttons) {
                session.controller.releaseButton(session.controller.resolveButton(name))
            }
            call.respond(ButtonStateResponse("ok", session.controller.getHeldButtons()))
        }

        post("/release-all") {
            session.controller.releaseAll()
            call.respond(ButtonStateResponse("ok", emptyList()))
        }

        // FM2 — NOT delegated: no toolset method for FM2 playback
        post("/fm2") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val body = call.receiveText()
            var framesExecuted = 0
            for (line in body.lines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("|")) continue
                val parts = trimmed.split("|")
                if (parts.size < 3) continue
                val input = parts[2]
                if (input.length < 8) continue

                session.controller.releaseAll()
                if (input[0] != '.') session.controller.pressButton(InputHandler.KEY_RIGHT)
                if (input[1] != '.') session.controller.pressButton(InputHandler.KEY_LEFT)
                if (input[2] != '.') session.controller.pressButton(InputHandler.KEY_DOWN)
                if (input[3] != '.') session.controller.pressButton(InputHandler.KEY_UP)
                if (input[4] != '.') session.controller.pressButton(InputHandler.KEY_START)
                if (input[5] != '.') session.controller.pressButton(InputHandler.KEY_SELECT)
                if (input[6] != '.') session.controller.pressButton(InputHandler.KEY_B)
                if (input[7] != '.') session.controller.pressButton(InputHandler.KEY_A)

                session.advanceFrames(1)
                framesExecuted++
            }
            call.respond(Fm2Response(framesExecuted, session.frameCount))
        }
    }
}
