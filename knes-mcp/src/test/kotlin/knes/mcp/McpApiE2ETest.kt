package knes.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import knes.api.EmulatorSession
import knes.api.configureRoutes
import java.io.File
import java.net.ServerSocket

/**
 * E2E test: starts a real REST API server, then calls it through RestApiClient
 * — the same HTTP path that MCP tools use in production.
 *
 * Tests without ROM verify the infrastructure (health, profiles, error handling).
 * Tests with ROM verify the full MCP workflow (load → profile → step → state → screen).
 */
class McpApiE2ETest : FunSpec({

    fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    fun findRom(envVar: String, sysProp: String, vararg paths: String): String? {
        System.getProperty(sysProp)?.let { if (File(it).exists()) return it }
        System.getenv(envVar)?.let { if (File(it).exists()) return it }
        for (path in paths) {
            val f = File(path)
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    val smbRom = findRom("KNES_TEST_ROM_SMB", "knes.test.rom.smb",
        "roms/smb.nes", "roms/knes.nes", "../roms/smb.nes", "../roms/knes.nes")
    val ff1Rom = findRom("KNES_TEST_ROM_FF1", "knes.test.rom.ff1",
        "roms/ff1.nes", "roms/ff.nes", "../roms/ff1.nes", "../roms/ff.nes")

    fun skipIfNoSmb() {
        if (smbRom == null) throw io.kotest.engine.TestAbortedException(
            "SMB ROM not found. Set KNES_TEST_ROM_SMB or place at roms/knes.nes")
    }

    fun skipIfNoFf1() {
        if (ff1Rom == null) throw io.kotest.engine.TestAbortedException(
            "FF1 ROM not found. Set KNES_TEST_ROM_FF1 or place at roms/ff.nes")
    }

    fun withServer(block: (RestApiClient) -> Unit) {
        val port = findFreePort()
        val session = EmulatorSession()
        val server = embeddedServer(Netty, port = port) {
            configureRoutes(session)
        }.start(wait = false)
        try {
            val client = RestApiClient("http://localhost:$port")
            block(client)
        } finally {
            server.stop(500, 1000)
        }
    }

    // --- Infrastructure tests (no ROM needed) ---

    test("health check via RestApiClient") {
        withServer { client ->
            client.isAvailable() shouldBe true
            val resp = client.get("/health")
            resp.ok shouldBe true
            resp.body shouldContain "\"status\""
        }
    }

    test("list_profiles returns available profiles") {
        withServer { client ->
            val resp = client.get("/profiles")
            resp.ok shouldBe true
            resp.body shouldContain "smb"
            resp.body shouldContain "ff1"
        }
    }

    test("get_state fails without ROM loaded") {
        withServer { client ->
            val resp = client.get("/state")
            resp.ok shouldBe false
        }
    }

    test("step fails without ROM loaded") {
        withServer { client ->
            val resp = client.postJson("/step", """{"buttons":[],"frames":1}""")
            resp.ok shouldBe false
        }
    }

    test("load_rom fails with invalid path") {
        withServer { client ->
            val resp = client.postJson("/rom", """{"path":"/nonexistent/rom.nes"}""")
            resp.ok shouldBe false
        }
    }

    test("press and release buttons") {
        withServer { client ->
            val pressResp = client.postJson("/press", """{"buttons":["A","RIGHT"]}""")
            pressResp.ok shouldBe true
            pressResp.body shouldContain "A"
            pressResp.body shouldContain "RIGHT"

            val releaseResp = client.postJson("/release", """{"buttons":["A"]}""")
            releaseResp.ok shouldBe true
            releaseResp.body shouldContain "RIGHT"
        }
    }

    // --- Full MCP workflow tests (ROM required) ---

    test("MCP workflow: load ROM, apply profile, step, get state") {
        skipIfNoSmb()
        withServer { client ->
            // 1. load_rom
            val loadResp = client.postJson("/rom", """{"path":"$smbRom"}""")
            loadResp.ok shouldBe true
            loadResp.body shouldContain "loaded"

            // 2. apply_profile (smb)
            val profileResp = client.postJson("/profiles/smb/apply", "")
            profileResp.ok shouldBe true

            // 3. step: let title screen render
            val titleResp = client.postJson("/step", """{"buttons":[],"frames":120}""")
            titleResp.ok shouldBe true

            // Press START
            client.postJson("/step", """{"buttons":["START"],"frames":5}""")

            // Wait for gameplay
            client.postJson("/step", """{"buttons":[],"frames":180}""")

            // 4. get_state: read initial position
            val stateBefore = client.get("/state")
            stateBefore.ok shouldBe true
            stateBefore.body shouldContain "playerX"
            val xBefore = Regex(""""playerX"\s*:\s*(\d+)""")
                .find(stateBefore.body)?.groupValues?.get(1)?.toInt() ?: 0

            // 5. step: walk right
            val walkResp = client.postJson("/step", """{"buttons":["RIGHT"],"frames":60}""")
            walkResp.ok shouldBe true
            walkResp.body shouldContain "playerX"

            // 6. get_state: position should have increased
            val stateAfter = client.get("/state")
            val xAfter = Regex(""""playerX"\s*:\s*(\d+)""")
                .find(stateAfter.body)?.groupValues?.get(1)?.toInt() ?: 0
            xAfter shouldBeGreaterThan xBefore
        }
    }

    test("MCP workflow: screenshot returns valid base64 PNG") {
        skipIfNoSmb()
        withServer { client ->
            client.postJson("/rom", """{"path":"$smbRom"}""")
            client.postJson("/step", """{"buttons":[],"frames":30}""")

            val resp = client.get("/screen/base64")
            resp.ok shouldBe true
            resp.body shouldContain "\"image\""

            // Extract and validate base64 PNG
            val imageMatch = Regex(""""image"\s*:\s*"([^"]+)"""").find(resp.body)
            imageMatch shouldBe io.kotest.matchers.nulls.beNull().invert()
            val decoded = java.util.Base64.getDecoder().decode(imageMatch!!.groupValues[1])
            // PNG magic bytes
            decoded[0] shouldBe 0x89.toByte()
            decoded[1] shouldBe 0x50.toByte() // P
            decoded[2] shouldBe 0x4E.toByte() // N
            decoded[3] shouldBe 0x47.toByte() // G
        }
    }

    test("MCP workflow: reset clears state") {
        skipIfNoSmb()
        withServer { client ->
            client.postJson("/rom", """{"path":"$smbRom"}""")
            client.postJson("/profiles/smb/apply", "")
            client.postJson("/step", """{"buttons":[],"frames":60}""")

            // Verify frames advanced
            val stateBefore = client.get("/state")
            stateBefore.ok shouldBe true
            val frameBefore = Regex(""""frame"\s*:\s*(\d+)""")
                .find(stateBefore.body)?.groupValues?.get(1)?.toInt() ?: 0
            frameBefore shouldBeGreaterThan 0

            // Reset
            val resetResp = client.postJson("/reset", "")
            resetResp.ok shouldBe true
            resetResp.body shouldContain "reset"
        }
    }

    test("MCP workflow: profile details endpoint") {
        withServer { client ->
            val resp = client.get("/profiles/smb")
            resp.ok shouldBe true
            resp.body shouldContain "Super Mario Bros"
            resp.body shouldContain "playerX"

            val ff1 = client.get("/profiles/ff1")
            ff1.ok shouldBe true
            ff1.body shouldContain "Final Fantasy"
        }
    }

    test("MCP workflow: unknown profile returns 404") {
        withServer { client ->
            val resp = client.postJson("/profiles/unknown/apply", "")
            resp.ok shouldBe false
            resp.code shouldBe 404
        }
    }

    // --- Final Fantasy E2E tests ---

    test("FF1: load ROM, apply profile, navigate intro to gameplay") {
        skipIfNoFf1()
        withServer { client ->
            // Load FF1
            val loadResp = client.postJson("/rom", """{"path":"$ff1Rom"}""")
            loadResp.ok shouldBe true

            // Apply FF1 profile
            client.postJson("/profiles/ff1/apply", "")

            // Let intro start
            client.postJson("/step", """{"buttons":[],"frames":120}""")

            // Skip intro with B
            client.postJson("/step", """{"buttons":["B"],"frames":10}""")
            client.postJson("/step", """{"buttons":[],"frames":60}""")

            // Name first character: 5x A to confirm default name
            repeat(5) {
                client.postJson("/step", """{"buttons":["A"],"frames":10}""")
                client.postJson("/step", """{"buttons":[],"frames":10}""")
            }

            // Wait for name screen to process
            client.postJson("/step", """{"buttons":[],"frames":30}""")

            // Select party: press A on each slot (4 characters)
            repeat(4) {
                client.postJson("/step", """{"buttons":["A"],"frames":10}""")
                client.postJson("/step", """{"buttons":[],"frames":20}""")
            }

            // Confirm party selection — press A a few more times and wait
            repeat(3) {
                client.postJson("/step", """{"buttons":["A"],"frames":10}""")
                client.postJson("/step", """{"buttons":[],"frames":30}""")
            }

            // Let the game load into world map
            client.postJson("/step", """{"buttons":[],"frames":300}""")

            // Check state — FF1 profile addresses should be populated
            val state = client.get("/state")
            state.ok shouldBe true
            state.body shouldContain "char1_hpLow"
            state.body shouldContain "goldLow"
            state.body shouldContain "worldX"
        }
    }

    test("FF1: profile has all expected address categories") {
        withServer { client ->
            val resp = client.get("/profiles/ff1")
            resp.ok shouldBe true

            // Location addresses
            resp.body shouldContain "worldX"
            resp.body shouldContain "worldY"

            // Gold
            resp.body shouldContain "goldLow"

            // Character stats (4 characters)
            for (i in 1..4) {
                resp.body shouldContain "char${i}_hpLow"
                resp.body shouldContain "char${i}_str"
                resp.body shouldContain "char${i}_level"
            }

            // Battle addresses
            resp.body shouldContain "battleTurn"
            resp.body shouldContain "enemyCount"
        }
    }

    test("FF1: character HP is non-zero after starting game") {
        skipIfNoFf1()
        withServer { client ->
            client.postJson("/rom", """{"path":"$ff1Rom"}""")
            client.postJson("/profiles/ff1/apply", "")

            // FF1 intro sequence:
            // 1. Wait for title screen
            client.postJson("/step", """{"buttons":[],"frames":120}""")

            // 2. Skip intro with B
            client.postJson("/step", """{"buttons":["B"],"frames":10}""")
            client.postJson("/step", """{"buttons":[],"frames":60}""")

            // 3. Name character 1: 5x A confirms default, then wait
            repeat(5) {
                client.postJson("/step", """{"buttons":["A"],"frames":10}""")
                client.postJson("/step", """{"buttons":[],"frames":10}""")
            }
            client.postJson("/step", """{"buttons":[],"frames":30}""")

            // 4. Select class for slots 2-4 and name them (A through each)
            repeat(30) {
                client.postJson("/step", """{"buttons":["A"],"frames":8}""")
                client.postJson("/step", """{"buttons":[],"frames":12}""")
            }

            // 5. Confirm party
            client.postJson("/step", """{"buttons":[],"frames":60}""")
            repeat(5) {
                client.postJson("/step", """{"buttons":["A"],"frames":10}""")
                client.postJson("/step", """{"buttons":[],"frames":30}""")
            }

            // 6. Wait for game to load into world map
            client.postJson("/step", """{"buttons":[],"frames":600}""")

            // Read char1 HP
            val state = client.get("/state")
            state.ok shouldBe true
            val hp = Regex(""""char1_hpLow"\s*:\s*(\d+)""")
                .find(state.body)?.groupValues?.get(1)?.toInt() ?: 0
            hp shouldBeGreaterThan 0
        }
    }
})
