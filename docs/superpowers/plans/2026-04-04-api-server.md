# kNES API Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the kNES emulator as a REST API for AI agents, TAS tools, and external clients.

**Architecture:** New `knes-api` module with Ktor embedded server. `ApiController` implements `ControllerProvider` (standard controller interface). `EmulatorSession` wraps headless NES lifecycle. Routes translate HTTP to emulator operations.

**Tech Stack:** Ktor 3.4.0 (server-netty, content-negotiation, kotlinx-json), Kotlin 2.3.20

---

## File Map

### New Files

| File | Purpose |
|------|---------|
| `knes-api/build.gradle` | Module build config with Ktor deps |
| `knes-api/src/main/kotlin/knes/api/ApiController.kt` | `ControllerProvider` impl driven by REST |
| `knes-api/src/main/kotlin/knes/api/EmulatorSession.kt` | NES lifecycle wrapper (load, step, state, screen) |
| `knes-api/src/main/kotlin/knes/api/ApiServer.kt` | Ktor routes and server setup |
| `knes-api/src/main/kotlin/knes/api/Main.kt` | Entry point |

### Modified Files

| File | Change |
|------|--------|
| `settings.gradle` | Add `include 'knes-api'` |

---

### Task 1: Module Setup and Dependencies

**Files:**
- Create: `knes-api/build.gradle`
- Modify: `settings.gradle`

- [ ] **Step 1: Add module to settings.gradle**

Add after the `include 'knes-compose-ui'` line:

```groovy
include 'knes-api'
```

- [ ] **Step 2: Create knes-api/build.gradle**

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
    id 'application'
    id 'org.jetbrains.kotlin.plugin.serialization' version '2.3.20'
}

repositories {
    mavenCentral()
}

def ktorVersion = '3.1.3'

dependencies {
    implementation project(':knes-emulator')
    implementation project(':knes-controllers')

    implementation "io.ktor:ktor-server-core:$ktorVersion"
    implementation "io.ktor:ktor-server-netty:$ktorVersion"
    implementation "io.ktor:ktor-server-content-negotiation:$ktorVersion"
    implementation "io.ktor:ktor-serialization-kotlinx-json:$ktorVersion"

    testImplementation 'io.kotest:kotest-runner-junit5:6.1.4'
    testImplementation 'io.kotest:kotest-assertions-core:6.1.4'
    testImplementation "io.ktor:ktor-server-test-host:$ktorVersion"
    testImplementation "io.ktor:ktor-client-content-negotiation:$ktorVersion"
}

kotlin {
    jvmToolchain(11)
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    kotlinOptions {
        jvmTarget = '11'
        apiVersion = '2.3'
        languageVersion = '2.3'
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

test {
    useJUnitPlatform()
}

application {
    mainClass = 'knes.api.MainKt'
}
```

- [ ] **Step 3: Create source directories**

```bash
mkdir -p knes-api/src/main/kotlin/knes/api
mkdir -p knes-api/src/test/kotlin/knes/api
```

- [ ] **Step 4: Verify module resolves**

Run: `./gradlew :knes-api:dependencies`

Expected: BUILD SUCCESSFUL showing Ktor and knes-emulator dependencies

- [ ] **Step 5: Commit**

```bash
git add settings.gradle knes-api/build.gradle
git commit -m "Add knes-api module with Ktor dependencies"
```

---

### Task 2: ApiController (ControllerProvider)

**Files:**
- Create: `knes-api/src/main/kotlin/knes/api/ApiController.kt`

- [ ] **Step 1: Create ApiController**

```kotlin
package knes.api

import knes.controllers.ControllerProvider
import knes.emulator.input.InputHandler

class ApiController : ControllerProvider {
    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    private val buttonNames = mapOf(
        "A" to InputHandler.KEY_A,
        "B" to InputHandler.KEY_B,
        "START" to InputHandler.KEY_START,
        "SELECT" to InputHandler.KEY_SELECT,
        "UP" to InputHandler.KEY_UP,
        "DOWN" to InputHandler.KEY_DOWN,
        "LEFT" to InputHandler.KEY_LEFT,
        "RIGHT" to InputHandler.KEY_RIGHT,
    )

    fun pressButton(key: Int) {
        keyStates[key] = 0x41
    }

    fun releaseButton(key: Int) {
        keyStates[key] = 0x40
    }

    fun releaseAll() {
        keyStates.fill(0x40)
    }

    fun setButtons(buttons: List<String>) {
        releaseAll()
        for (name in buttons) {
            val key = buttonNames[name.uppercase()]
                ?: throw IllegalArgumentException("Unknown button: $name. Valid: ${buttonNames.keys}")
            pressButton(key)
        }
    }

    fun getHeldButtons(): List<String> {
        return buttonNames.entries
            .filter { keyStates[it.value] == 0x41.toShort() }
            .map { it.key }
    }

    fun resolveButton(name: String): Int {
        return buttonNames[name.uppercase()]
            ?: throw IllegalArgumentException("Unknown button: $name. Valid: ${buttonNames.keys}")
    }

    override fun setKeyState(keyCode: Int, isPressed: Boolean) {
        // Not used — API controls buttons via pressButton/releaseButton
    }

    override fun getKeyState(padKey: Int): Short = keyStates[padKey]
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :knes-api:compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiController.kt
git commit -m "Add ApiController implementing ControllerProvider for REST input"
```

---

### Task 3: EmulatorSession

**Files:**
- Create: `knes-api/src/main/kotlin/knes/api/EmulatorSession.kt`

- [ ] **Step 1: Create EmulatorSession**

```kotlin
package knes.api

import knes.emulator.NES
import knes.emulator.input.InputHandler
import knes.emulator.ui.GUI
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class EmulatorSession {
    val controller = ApiController()

    var frameCount: Int = 0
        private set

    var romLoaded: Boolean = false
        private set

    private var currentBuffer = IntArray(256 * 240)
    private var watchedAddresses: MutableMap<String, Int> = mutableMapOf()

    private val inputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = controller.getKeyState(padKey)
    }

    val nes: NES

    init {
        Globals.appletMode = true
        Globals.enableSound = false
        Globals.palEmulation = false
        Globals.timeEmulation = false

        val gui = object : GUI {
            override fun sendErrorMsg(message: String) {}
            override fun sendDebugMessage(message: String) {}
            override fun destroy() {}
            override fun getJoy1(): InputHandler = inputHandler
            override fun getJoy2(): InputHandler? = null
            override fun getTimer(): HiResTimer = HiResTimer()
            override fun imageReady(skipFrame: Boolean, buffer: IntArray) {
                System.arraycopy(buffer, 0, currentBuffer, 0, buffer.size)
                frameCount++
            }
        }

        nes = NES(gui)
    }

    fun loadRom(path: String): Boolean {
        romLoaded = nes.loadRom(path)
        if (romLoaded) {
            frameCount = 0
        }
        return romLoaded
    }

    fun reset() {
        nes.reset()
        frameCount = 0
        controller.releaseAll()
    }

    fun advanceFrames(n: Int) {
        val target = frameCount + n
        val maxSteps = n * 300_000
        var steps = 0
        while (frameCount < target) {
            nes.cpu.step()
            if (++steps > maxSteps) {
                throw IllegalStateException("advanceFrames($n) timed out after $maxSteps steps")
            }
        }
    }

    fun readMemory(addr: Int): Int {
        return nes.cpuMemory.load(addr).toInt() and 0xFF
    }

    fun setWatchedAddresses(addresses: Map<String, Int>) {
        watchedAddresses.clear()
        watchedAddresses.putAll(addresses)
    }

    fun getWatchedState(): Map<String, Int> {
        return watchedAddresses.mapValues { readMemory(it.value) }
    }

    fun getScreenPng(): ByteArray {
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 256, 240, currentBuffer, 0, 256)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun getScreenBase64(): String {
        return java.util.Base64.getEncoder().encodeToString(getScreenPng())
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :knes-api:compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/EmulatorSession.kt
git commit -m "Add EmulatorSession wrapping headless NES lifecycle"
```

---

### Task 4: API Server Routes

**Files:**
- Create: `knes-api/src/main/kotlin/knes/api/ApiServer.kt`
- Create: `knes-api/src/main/kotlin/knes/api/Main.kt`

- [ ] **Step 1: Create ApiServer with all routes**

```kotlin
package knes.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RomRequest(val path: String)

@Serializable
data class StepRequest(val buttons: List<String> = emptyList(), val frames: Int = 1)

@Serializable
data class StepSequence(val sequence: List<StepRequest>)

@Serializable
data class ButtonsRequest(val buttons: List<String>)

@Serializable
data class WatchRequest(val addresses: Map<String, String>)

@Serializable
data class StatusResponse(val status: String, val romLoaded: Boolean = false, val frames: Int = 0)

@Serializable
data class StepResponse(val frame: Int, val ram: Map<String, Int> = emptyMap())

@Serializable
data class ScreenBase64Response(val frame: Int, val image: String)

@Serializable
data class StateResponse(
    val frame: Int,
    val ram: Map<String, Int>,
    val buttons: List<String>,
    val cpu: CpuState
)

@Serializable
data class CpuState(val pc: Int, val a: Int, val x: Int, val y: Int, val sp: Int)

@Serializable
data class Fm2Response(val framesExecuted: Int, val frame: Int)

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

            val contentType = call.request.contentType()
            if (contentType.match(ContentType.Application.Json)) {
                val text = call.receiveText()
                // Try sequence first, fall back to single step
                try {
                    val seq = Json.decodeFromString<StepSequence>(text)
                    for (step in seq.sequence) {
                        session.controller.setButtons(step.buttons)
                        session.advanceFrames(step.frames)
                    }
                } catch (e: Exception) {
                    val req = Json.decodeFromString<StepRequest>(text)
                    session.controller.setButtons(req.buttons)
                    session.advanceFrames(req.frames)
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
                val hex = v.removePrefix("0x").removePrefix("0X")
                hex.toInt(16)
            }
            session.setWatchedAddresses(addresses)
            call.respond(StatusResponse("ok", session.romLoaded, session.frameCount))
        }

        post("/press") {
            val req = call.receive<ButtonsRequest>()
            for (name in req.buttons) {
                session.controller.pressButton(session.controller.resolveButton(name))
            }
            call.respond(mapOf("status" to "ok", "held" to session.controller.getHeldButtons()))
        }

        post("/release") {
            val req = call.receive<ButtonsRequest>()
            for (name in req.buttons) {
                session.controller.releaseButton(session.controller.resolveButton(name))
            }
            call.respond(mapOf("status" to "ok", "held" to session.controller.getHeldButtons()))
        }

        post("/release-all") {
            session.controller.releaseAll()
            call.respond(mapOf("status" to "ok", "held" to emptyList<String>()))
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

                // parts[0] = "" (before first pipe)
                // parts[1] = commands
                // parts[2] = controller 1 (RLDUTSBA)
                val input = parts[2]
                if (input.length < 8) continue

                session.controller.releaseAll()
                // FM2 button order: R L D U T S B A
                if (input[0] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_RIGHT)
                if (input[1] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_LEFT)
                if (input[2] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_DOWN)
                if (input[3] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_UP)
                if (input[4] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_START)
                if (input[5] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_SELECT)
                if (input[6] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_B)
                if (input[7] != '.') session.controller.pressButton(knes.emulator.input.InputHandler.KEY_A)

                session.advanceFrames(1)
                framesExecuted++
            }

            call.respond(Fm2Response(framesExecuted, session.frameCount))
        }
    }
}
```

- [ ] **Step 2: Create Main.kt**

```kotlin
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
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :knes-api:compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiServer.kt knes-api/src/main/kotlin/knes/api/Main.kt
git commit -m "Add Ktor API server with all REST endpoints"
```

---

### Task 5: Integration Test

**Files:**
- Create: `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt`

- [ ] **Step 1: Create API server test**

```kotlin
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
            val session = EmulatorSession()
            application { configureRoutes(session) }

            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\" : \"ok\""
        }
    }

    test("POST /step without ROM returns 400") {
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            val response = client.post("/step") {
                contentType(ContentType.Application.Json)
                setBody("""{"buttons": [], "frames": 1}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /watch configures addresses") {
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            val response = client.post("/watch") {
                contentType(ContentType.Application.Json)
                setBody("""{"addresses": {"playerX": "0x0086"}}""")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("POST /press and /release manage button state") {
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

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

            val releaseAllResponse = client.post("/release-all")
            releaseAllResponse.status shouldBe HttpStatusCode.OK
        }
    }

    test("POST /reset works without ROM") {
        testApplication {
            val session = EmulatorSession()
            application { configureRoutes(session) }

            val response = client.post("/reset")
            response.status shouldBe HttpStatusCode.OK
        }
    }
})
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :knes-api:test`

Expected: BUILD SUCCESSFUL — all tests pass

- [ ] **Step 3: Commit**

```bash
git add knes-api/src/test/kotlin/knes/api/ApiServerTest.kt
git commit -m "Add API server integration tests"
```

---

### Task 6: Manual Smoke Test

- [ ] **Step 1: Start the server**

Run: `./gradlew :knes-api:run`

Expected: "kNES API Server starting on port 8080"

- [ ] **Step 2: Test health endpoint**

Run: `curl localhost:8080/health`

Expected: `{"status":"ok","romLoaded":false,"frames":0}`

- [ ] **Step 3: Load a ROM (if available)**

Run: `curl -X POST localhost:8080/rom -H 'Content-Type: application/json' -d '{"path": "/absolute/path/to/rom.nes"}'`

Expected: `{"status":"loaded","romLoaded":true,"frames":0}`

- [ ] **Step 4: Step and check state**

```bash
curl -X POST localhost:8080/step -H 'Content-Type: application/json' -d '{"buttons": [], "frames": 60}'
curl localhost:8080/screen -o frame.png
```

Expected: PNG file saved, frame count advanced

- [ ] **Step 5: Run full suite to verify no regressions**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL — all tests across all modules pass
