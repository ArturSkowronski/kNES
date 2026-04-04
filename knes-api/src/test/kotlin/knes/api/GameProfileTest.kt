package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

class GameProfileTest : FunSpec({

    test("GET /profiles lists available profiles") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/profiles")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "smb"
            response.bodyAsText() shouldContain "ff1"
        }
    }

    test("GET /profiles/smb returns SMB profile") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/profiles/smb")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Super Mario Bros"
            response.bodyAsText() shouldContain "playerX"
        }
    }

    test("GET /profiles/ff1 returns FF1 profile") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/profiles/ff1")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Final Fantasy"
            response.bodyAsText() shouldContain "char1_hpLow"
        }
    }

    test("GET /profiles/unknown returns 404") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.get("/profiles/unknown")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /profiles/smb/apply sets watched addresses") {
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }
            val response = client.post("/profiles/smb/apply")
            response.status shouldBe HttpStatusCode.OK
            session.getWatchedState().containsKey("playerX") shouldBe true
        }
    }

    test("POST /profiles registers custom profile") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }
            val response = client.post("/profiles") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"custom","name":"Custom Game","addresses":{"foo":{"address":"0x1234","description":"test"}}}""")
            }
            response.status shouldBe HttpStatusCode.OK

            val getResponse = client.get("/profiles/custom")
            getResponse.status shouldBe HttpStatusCode.OK
            getResponse.bodyAsText() shouldContain "Custom Game"
        }
    }
})
