package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File

class ApiE2ETest : FunSpec({

    fun findRom(): String? {
        System.getProperty("knes.test.rom.smb")?.let { if (File(it).exists()) return it }
        System.getenv("KNES_TEST_ROM_SMB")?.let { if (File(it).exists()) return it }
        for (path in listOf("roms/smb.nes", "roms/knes.nes", "../roms/smb.nes", "../roms/knes.nes")) {
            val f = File(path)
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    val romPath = findRom()

    fun skipIfNoRom() {
        if (romPath == null) {
            throw io.kotest.engine.TestAbortedException(
                "SMB ROM not found. Set KNES_TEST_ROM_SMB env var or place ROM at roms/smb.nes"
            )
        }
    }

    test("full game session through REST API: load ROM, start game, walk right") {
        skipIfNoRom()
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            // Load ROM
            val loadResponse = client.post("/rom") {
                contentType(ContentType.Application.Json)
                setBody("""{"path": "$romPath"}""")
            }
            loadResponse.status shouldBe HttpStatusCode.OK
            loadResponse.bodyAsText() shouldContain "loaded"

            // Configure RAM watches
            val watchResponse = client.post("/watch") {
                contentType(ContentType.Application.Json)
                setBody("""{"addresses": {"playerX": "0x0086", "gameState": "0x0770"}}""")
            }
            watchResponse.status shouldBe HttpStatusCode.OK

            // Wait for title screen (2 seconds)
            val titleResponse = client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": [], "frames": 120}""")
            }
            titleResponse.status shouldBe HttpStatusCode.OK

            // Press Start to begin game
            client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": ["START"], "frames": 5}""")
            }

            // Wait for gameplay
            client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": [], "frames": 180}""")
            }

            // Read initial X position
            val stateBeforeBody = client.get("/state").bodyAsText()

            // Walk right for 1 second
            val walkResponse = client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": ["RIGHT"], "frames": 60}""")
            }
            walkResponse.status shouldBe HttpStatusCode.OK

            // Read final X position
            val stateAfterBody = client.get("/state").bodyAsText()

            // Parse playerX from JSON responses
            val xBefore = Regex(""""playerX"\s*:\s*(\d+)""").find(stateBeforeBody)?.groupValues?.get(1)?.toInt() ?: 0
            val xAfter = Regex(""""playerX"\s*:\s*(\d+)""").find(stateAfterBody)?.groupValues?.get(1)?.toInt() ?: 0

            xAfter shouldBeGreaterThan xBefore
        }
    }

    test("screenshot endpoint returns valid PNG after loading ROM") {
        skipIfNoRom()
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            client.post("/rom") {
                contentType(ContentType.Application.Json)
                setBody("""{"path": "$romPath"}""")
            }

            // Advance a few frames to render something
            client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": [], "frames": 30}""")
            }

            val screenResponse = client.get("/screen")
            screenResponse.status shouldBe HttpStatusCode.OK
            screenResponse.contentType()?.match(ContentType.Image.PNG) shouldBe true
            val bytes = screenResponse.readRawBytes()
            // PNG magic bytes: 0x89 P N G
            bytes[0] shouldBe 0x89.toByte()
            bytes[1] shouldBe 0x50.toByte() // P
            bytes[2] shouldBe 0x4E.toByte() // N
            bytes[3] shouldBe 0x47.toByte() // G
        }
    }

    test("FM2 input playback works through API") {
        skipIfNoRom()
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            client.post("/rom") {
                contentType(ContentType.Application.Json)
                setBody("""{"path": "$romPath"}""")
            }

            // Send 3 frames of FM2 input: right, right+A, nothing
            val fm2Response = client.post("/fm2") {
                contentType(ContentType.Text.Plain)
                setBody("|0|R.......|........|\n|0|R......A|........|\n|0|........|........|")
            }
            fm2Response.status shouldBe HttpStatusCode.OK
            fm2Response.bodyAsText() shouldContain "\"framesExecuted\""
            fm2Response.bodyAsText() shouldContain "3"
        }
    }

    test("batch step sequence executes atomically") {
        skipIfNoRom()
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            client.post("/rom") {
                contentType(ContentType.Application.Json)
                setBody("""{"path": "$romPath"}""")
            }

            val seqResponse = client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{
                    "sequence": [
                        {"buttons": [], "frames": 60},
                        {"buttons": ["START"], "frames": 5},
                        {"buttons": [], "frames": 60}
                    ]
                }""")
            }
            seqResponse.status shouldBe HttpStatusCode.OK
            // Should have advanced 125 frames total
            val body = seqResponse.bodyAsText()
            val frame = Regex(""""frame"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toInt() ?: 0
            frame shouldBe 125
        }
    }
})
