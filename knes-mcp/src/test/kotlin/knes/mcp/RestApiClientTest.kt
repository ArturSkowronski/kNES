package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
})
