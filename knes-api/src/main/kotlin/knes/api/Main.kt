package knes.api

import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val session = EmulatorSession()
    val port = System.getenv("KNES_PORT")?.toIntOrNull() ?: 8080
    println("kNES API Server starting on port $port")
    embeddedServer(Netty, port = port) {
        configureRoutes(session)
    }.start(wait = true)
}
