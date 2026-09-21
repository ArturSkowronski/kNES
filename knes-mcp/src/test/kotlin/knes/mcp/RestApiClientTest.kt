package knes.mcp

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class RestApiClientTest : FunSpec({

    test("isAvailable returns false when no server running") {
        val client = RestApiClient("http://localhost:19999") // unlikely port
        client.isAvailable() shouldBe false
    }

    test("get returns error when server not running") {
        val client = RestApiClient("http://localhost:19999")
        try {
            client.get("/health")
        } catch (e: Exception) {
            // Connection refused is expected
        }
    }

    test("postJson serializes structured JsonElement bodies safely") {
        val receivedBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/rom") { exchange ->
            receivedBody.set(exchange.requestBody.bufferedReader().readText())
            val response = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val client = RestApiClient("http://localhost:${server.address.port}")
            val response = client.postJson(
                "/rom",
                buildJsonObject {
                    put("path", """C:\ROMs\"quoted".nes""")
                }
            )

            response.ok shouldBe true
            receivedBody.get() shouldBe """{"path":"C:\\ROMs\\\"quoted\".nes"}"""
        } finally {
            server.stop(0)
        }
    }
})
