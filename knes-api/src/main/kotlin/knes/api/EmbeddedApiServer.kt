package knes.api

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import knes.emulator.NES

/**
 * Embedded API server that runs alongside the Compose UI on the same NES instance.
 *
 * In shared mode:
 * - /state, /screen, /watch, /profiles, /health work normally
 * - /press, /release work (input merges with keyboard/gamepad)
 * - /step, /reset work (MCP can advance frames and reset)
 * - /rom returns 400 (UI loads ROMs)
 */
class EmbeddedApiServer(
    nes: NES,
    private val port: Int = 6502
) {
    val session = EmulatorSession(externalNes = nes)
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(Netty, port = port) {
            configureRoutes(session)
        }.start(wait = false)
        println("kNES Embedded API Server started on port $port")
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
        println("kNES Embedded API Server stopped")
    }

    val isRunning: Boolean get() = server != null
}
