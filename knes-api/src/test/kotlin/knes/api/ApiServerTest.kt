package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

class ApiServerTest : FunSpec({

    test("GET /health returns ok") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\""
            response.bodyAsText() shouldContain "\"ok\""
        }
    }

    test("POST /step without ROM returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": [], "frames": 1}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /screen without ROM returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/screen")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /screen/base64 without ROM returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/screen/base64")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /state without ROM returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/state")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /fm2 without ROM returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/fm2") {
                contentType(ContentType.Text.Plain)
                setBody("|0|R.......|........|")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /watch configures addresses") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/watch") {
                contentType(ContentType.Application.Json)
                setBody("""{"addresses": {"playerX": "0x0086", "lives": "0x075A"}}""")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("POST /press and /release manage button state") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }

            val pressResponse = client.post("/press") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": ["RIGHT", "A"]}""")
            }
            pressResponse.status shouldBe HttpStatusCode.OK
            pressResponse.bodyAsText() shouldContain "RIGHT"
            pressResponse.bodyAsText() shouldContain "A"

            val releaseResponse = client.post("/release") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": ["RIGHT"]}""")
            }
            releaseResponse.status shouldBe HttpStatusCode.OK
            releaseResponse.bodyAsText() shouldContain "A"
        }
    }

    test("POST /release-all clears all buttons") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }

            client.post("/press") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": ["UP", "DOWN", "A", "B"]}""")
            }

            val response = client.post("/release-all")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"held\""
            response.bodyAsText() shouldContain "[]"
        }
    }

    test("POST /reset works") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/reset")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\""
            response.bodyAsText() shouldContain "\"reset\""
        }
    }

    test("POST /rom with invalid path returns 400") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/rom") {
                contentType(ContentType.Application.Json)
                setBody("""{"path": "/nonexistent/rom.nes"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /press works independently of queue") {
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            // Load a ROM to enable /step — use press/release to verify controller wiring
            // Without a ROM we can't test step execution, but we CAN test that
            // press still works independently of the queue
            val pressResponse = client.post("/press") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": ["A"]}""")
            }
            pressResponse.status shouldBe HttpStatusCode.OK
            pressResponse.bodyAsText() shouldContain "A"
        }
    }
})
