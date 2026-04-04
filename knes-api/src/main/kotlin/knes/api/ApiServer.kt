package knes.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import knes.emulator.input.InputHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class RomRequest(val path: String)
@Serializable data class StepRequest(val buttons: List<String> = emptyList(), val frames: Int = 1)
@Serializable data class StepSequence(val sequence: List<StepRequest>)
@Serializable data class ButtonsRequest(val buttons: List<String>)
@Serializable data class WatchRequest(val addresses: Map<String, String>)
@Serializable data class StatusResponse(val status: String, val romLoaded: Boolean = false, val frames: Int = 0)
@Serializable data class StepResponse(val frame: Int, val ram: Map<String, Int> = emptyMap())
@Serializable data class ScreenBase64Response(val frame: Int, val image: String)
@Serializable data class StateResponse(val frame: Int, val ram: Map<String, Int>, val buttons: List<String>, val cpu: CpuState)
@Serializable data class CpuState(val pc: Int, val a: Int, val x: Int, val y: Int, val sp: Int)
@Serializable data class Fm2Response(val framesExecuted: Int, val frame: Int)
@Serializable data class ButtonStateResponse(val status: String, val held: List<String>)

fun Application.configureRoutes(session: EmulatorSession) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }

    routing {
        get("/health") {
            call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
        }

        post("/rom") {
            val req = call.receive<RomRequest>()
            val loaded = session.loadRom(req.path)
            if (loaded) {
                call.respond(StatusResponse("loaded", romLoaded = true))
            } else {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("failed"))
            }
        }

        post("/reset") {
            session.reset()
            call.respond(StatusResponse("reset", session.romLoaded, session.frameCount))
        }

        post("/step") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val text = call.receiveText()
            try {
                val seq = Json.decodeFromString<StepSequence>(text)
                for (step in seq.sequence) {
                    session.controller.setButtons(step.buttons)
                    session.advanceFrames(step.frames)
                }
            } catch (e: Exception) {
                try {
                    val req = Json.decodeFromString<StepRequest>(text)
                    session.controller.setButtons(req.buttons)
                    session.advanceFrames(req.frames)
                } catch (e2: Exception) {
                    call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid request: ${e2.message}"))
                    return@post
                }
            }
            call.respond(StepResponse(session.frameCount, session.getWatchedState()))
        }

        get("/screen") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@get
            }
            call.respondBytes(session.getScreenPng(), ContentType.Image.PNG)
        }

        get("/screen/base64") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@get
            }
            call.respond(ScreenBase64Response(session.frameCount, session.getScreenBase64()))
        }

        get("/state") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@get
            }
            call.respond(StateResponse(
                frame = session.frameCount,
                ram = session.getWatchedState(),
                buttons = session.controller.getHeldButtons(),
                cpu = CpuState(
                    pc = session.nes.cpu.REG_PC_NEW,
                    a = session.nes.cpu.REG_ACC_NEW,
                    x = session.nes.cpu.REG_X_NEW,
                    y = session.nes.cpu.REG_Y_NEW,
                    sp = session.nes.cpu.REG_SP
                )
            ))
        }

        post("/watch") {
            val req = call.receive<WatchRequest>()
            val addresses = req.addresses.mapValues { (_, v) ->
                v.removePrefix("0x").removePrefix("0X").toInt(16)
            }
            session.setWatchedAddresses(addresses)
            call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
        }

        post("/press") {
            val req = call.receive<ButtonsRequest>()
            for (name in req.buttons) {
                session.controller.pressButton(session.controller.resolveButton(name))
            }
            call.respond(ButtonStateResponse("ok", session.controller.getHeldButtons()))
        }

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
