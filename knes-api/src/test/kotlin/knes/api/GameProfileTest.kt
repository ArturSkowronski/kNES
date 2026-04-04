package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

class GameProfileTest : FunSpec({

    test("builtin profiles are loaded") {
        val profiles = GameProfile.list()
        profiles.size shouldBeGreaterThan 0
        GameProfile.get("smb") shouldNotBe null
        GameProfile.get("ff1") shouldNotBe null
    }

    test("SMB profile has expected addresses") {
        val smb = GameProfile.get("smb")!!
        smb.name shouldBe "Super Mario Bros"
        smb.addresses["playerX"] shouldNotBe null
        smb.addresses["lives"] shouldNotBe null
        smb.addresses["world"] shouldNotBe null
        smb.toWatchMap()["playerX"] shouldBe 0x0086
    }

    test("FF1 profile has expected addresses") {
        val ff1 = GameProfile.get("ff1")!!
        ff1.name shouldBe "Final Fantasy"
        ff1.addresses["char1_hpLow"] shouldNotBe null
        ff1.addresses["goldLow"] shouldNotBe null
        ff1.toWatchMap()["char1_hpLow"] shouldBe 0x610A
        ff1.toWatchMap()["goldLow"] shouldBe 0x601C
    }

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

            val state = session.getWatchedState()
            state.containsKey("playerX") shouldBe true
            state.containsKey("lives") shouldBe true
        }
    }

    test("POST /profiles registers custom profile") {
        testApplication {
            application { configureRoutes(EmulatorSession()) }

            val response = client.post("/profiles") {
                contentType(ContentType.Application.Json)
                setBody("""{"id": "test", "name": "Test Game", "addresses": {"foo": {"address": "0x1234", "description": "test addr"}}}""")
            }
            response.status shouldBe HttpStatusCode.OK

            val getResponse = client.get("/profiles/test")
            getResponse.status shouldBe HttpStatusCode.OK
            getResponse.bodyAsText() shouldContain "Test Game"
        }
    }
})
