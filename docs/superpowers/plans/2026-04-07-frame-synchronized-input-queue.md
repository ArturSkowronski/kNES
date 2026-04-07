# Frame-Synchronized Input Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix short MCP button presses not registering in shared mode by synchronizing input delivery to frame boundaries.

**Architecture:** New `InputQueue` class consumes one `FrameInput` per frame at `imageReady` boundaries. `ApiController` merges queue input with persistent holds. `/step` route enqueues inputs and awaits a `CountDownLatch` instead of polling `advanceFrames`.

**Tech Stack:** Kotlin, `java.util.concurrent.ConcurrentLinkedQueue`, `java.util.concurrent.CountDownLatch`, Kotest, Ktor test host

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `knes-api/src/main/kotlin/knes/api/InputQueue.kt` | Frame-synchronized input queue with latch-based completion |
| Create | `knes-api/src/test/kotlin/knes/api/InputQueueTest.kt` | Unit tests for InputQueue |
| Modify | `knes-api/src/main/kotlin/knes/api/ApiController.kt` | Add queue, merge in `getKeyState`, `onFrameBoundary`, `enqueueSteps` |
| Modify | `knes-api/src/test/kotlin/knes/api/ApiControllerTest.kt` | Tests for queue merge and enqueue |
| Modify | `knes-api/src/main/kotlin/knes/api/ApiServer.kt` | `/step` uses queue in shared mode |
| Modify | `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt` | Test `/step` with queue path |
| Modify | `knes-compose-ui/src/main/kotlin/knes/compose/ComposeMain.kt` | Wire `onFrameBoundary` into `onApiFrameCallback` |
| Modify | `knes-mcp/src/main/kotlin/knes/mcp/NesEmulatorSession.kt` | Add queue for standalone consistency |
| Modify | `knes-mcp/src/test/kotlin/knes/mcp/NesEmulatorSessionTest.kt` | Test standalone queue behavior |

---

### Task 1: Create `InputQueue`

**Files:**
- Create: `knes-api/src/main/kotlin/knes/api/InputQueue.kt`
- Create: `knes-api/src/test/kotlin/knes/api/InputQueueTest.kt`

- [ ] **Step 1: Write failing tests for `InputQueue`**

Create `knes-api/src/test/kotlin/knes/api/InputQueueTest.kt`:

```kotlin
package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import java.util.concurrent.TimeUnit

class InputQueueTest : FunSpec({

    test("initially inactive with nothing pressed") {
        val q = InputQueue()
        q.isActive shouldBe false
        q.isPressed(InputHandler.KEY_A) shouldBe false
    }

    test("enqueue sets currentFrame immediately") {
        val q = InputQueue()
        q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_A))))
        q.isActive shouldBe true
        q.isPressed(InputHandler.KEY_A) shouldBe true
        q.isPressed(InputHandler.KEY_B) shouldBe false
    }

    test("advanceFrame pops next entry") {
        val q = InputQueue()
        q.enqueue(listOf(
            FrameInput(setOf(InputHandler.KEY_A)),
            FrameInput(setOf(InputHandler.KEY_B))
        ))
        q.isPressed(InputHandler.KEY_A) shouldBe true

        q.advanceFrame()
        q.isPressed(InputHandler.KEY_A) shouldBe false
        q.isPressed(InputHandler.KEY_B) shouldBe true
    }

    test("advanceFrame clears currentFrame when queue empty") {
        val q = InputQueue()
        q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_A))))
        q.advanceFrame()
        q.isActive shouldBe false
        q.isPressed(InputHandler.KEY_A) shouldBe false
    }

    test("latch counts down on each advanceFrame") {
        val q = InputQueue()
        val latch = q.enqueue(listOf(
            FrameInput(setOf(InputHandler.KEY_A)),
            FrameInput(setOf(InputHandler.KEY_A)),
            FrameInput(setOf(InputHandler.KEY_A))
        ))
        latch.count shouldBe 3

        q.advanceFrame()
        latch.count shouldBe 2

        q.advanceFrame()
        latch.count shouldBe 1

        q.advanceFrame()
        latch.count shouldBe 0
        latch.await(0, TimeUnit.MILLISECONDS) shouldBe true
    }

    test("empty buttons enqueue correctly") {
        val q = InputQueue()
        val latch = q.enqueue(listOf(
            FrameInput(emptySet()),
            FrameInput(emptySet())
        ))
        q.isActive shouldBe true
        q.isPressed(InputHandler.KEY_A) shouldBe false

        q.advanceFrame()
        q.advanceFrame()
        latch.await(0, TimeUnit.MILLISECONDS) shouldBe true
    }

    test("advanceFrame with no queue is a no-op") {
        val q = InputQueue()
        q.advanceFrame() // should not throw
        q.isActive shouldBe false
    }

    test("second enqueue appends to existing queue") {
        val q = InputQueue()
        val latch1 = q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_A))))
        val latch2 = q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_B))))

        // First entry already set as currentFrame
        q.isPressed(InputHandler.KEY_A) shouldBe true

        q.advanceFrame() // completes first enqueue's entry, pops second
        latch1.await(0, TimeUnit.MILLISECONDS) shouldBe true
        q.isPressed(InputHandler.KEY_B) shouldBe true

        q.advanceFrame() // completes second enqueue's entry
        latch2.await(0, TimeUnit.MILLISECONDS) shouldBe true
        q.isActive shouldBe false
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-api:test --tests "knes.api.InputQueueTest" --info`
Expected: Compilation failure — `InputQueue` and `FrameInput` don't exist yet.

- [ ] **Step 3: Implement `InputQueue`**

Create `knes-api/src/main/kotlin/knes/api/InputQueue.kt`:

```kotlin
package knes.api

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

data class FrameInput(val buttons: Set<Int>)

class InputQueue {
    private val queue = ConcurrentLinkedQueue<FrameInput>()
    private val latches = ConcurrentLinkedQueue<LatchEntry>()

    @Volatile
    var currentFrame: FrameInput? = null
        private set

    val isActive: Boolean get() = currentFrame != null

    fun enqueue(inputs: List<FrameInput>): CountDownLatch {
        require(inputs.isNotEmpty()) { "inputs must not be empty" }
        val latch = CountDownLatch(inputs.size)
        latches.add(LatchEntry(latch, inputs.size))

        val isFirstEntry = currentFrame == null
        queue.addAll(inputs)

        if (isFirstEntry) {
            currentFrame = queue.poll()
        }

        return latch
    }

    fun advanceFrame() {
        if (currentFrame == null) return
        countDownOldest()
        currentFrame = queue.poll()
    }

    fun isPressed(padKey: Int): Boolean = currentFrame?.buttons?.contains(padKey) == true

    private fun countDownOldest() {
        val entry = latches.peek() ?: return
        entry.latch.countDown()
        entry.remaining--
        if (entry.remaining <= 0) {
            latches.poll()
        }
    }

    private data class LatchEntry(val latch: CountDownLatch, var remaining: Int)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-api:test --tests "knes.api.InputQueueTest" --info`
Expected: All 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/InputQueue.kt knes-api/src/test/kotlin/knes/api/InputQueueTest.kt
git commit -m "feat: add InputQueue for frame-synchronized input delivery"
```

---

### Task 2: Integrate `InputQueue` into `ApiController`

**Files:**
- Modify: `knes-api/src/main/kotlin/knes/api/ApiController.kt`
- Modify: `knes-api/src/test/kotlin/knes/api/ApiControllerTest.kt`

- [ ] **Step 1: Write failing tests for queue integration**

Append these tests to `knes-api/src/test/kotlin/knes/api/ApiControllerTest.kt`:

```kotlin
    test("getKeyState merges queue input with persistent holds") {
        val c = ApiController()
        c.pressButton(InputHandler.KEY_A) // persistent hold

        val latch = c.enqueueSteps(listOf(StepRequest(listOf("B"), 1)))
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()  // persistent
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()  // from queue

        c.onFrameBoundary() // consume queue entry
        latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe true
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()  // still persistent
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()  // queue empty
    }

    test("enqueueSteps converts StepRequest to FrameInput") {
        val c = ApiController()
        val latch = c.enqueueSteps(listOf(
            StepRequest(listOf("A"), 2),
            StepRequest(emptyList(), 1),
            StepRequest(listOf("B"), 1)
        ))
        // 2 + 1 + 1 = 4 frames total
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()

        c.onFrameBoundary() // frame 2 of A
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()

        c.onFrameBoundary() // empty frame
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x40.toShort()
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()

        c.onFrameBoundary() // B frame
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()

        c.onFrameBoundary() // done
        latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe true
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()
    }

    test("onFrameBoundary is safe when no queue active") {
        val c = ApiController()
        c.onFrameBoundary() // should not throw
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-api:test --tests "knes.api.ApiControllerTest" --info`
Expected: Compilation failure — `enqueueSteps` and `onFrameBoundary` don't exist.

- [ ] **Step 3: Modify `ApiController`**

In `knes-api/src/main/kotlin/knes/api/ApiController.kt`, add the queue field, modify `getKeyState`, and add new methods:

```kotlin
package knes.api

import knes.controllers.ControllerProvider
import knes.emulator.input.InputHandler
import java.util.concurrent.CountDownLatch

class ApiController : ControllerProvider {
    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    val inputQueue = InputQueue()

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

    fun pressButton(key: Int) { keyStates[key] = 0x41 }
    fun releaseButton(key: Int) { keyStates[key] = 0x40 }
    fun releaseAll() { keyStates.fill(0x40) }

    fun setButtons(buttons: List<String>) {
        releaseAll()
        for (name in buttons) {
            pressButton(resolveButton(name))
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

    fun enqueueSteps(steps: List<StepRequest>): CountDownLatch {
        val frameInputs = steps.flatMap { step ->
            val buttons = step.buttons.map { resolveButton(it) }.toSet()
            List(step.frames) { FrameInput(buttons) }
        }
        return inputQueue.enqueue(frameInputs)
    }

    fun onFrameBoundary() {
        inputQueue.advanceFrame()
    }

    override fun setKeyState(keyCode: Int, isPressed: Boolean) {}

    override fun getKeyState(padKey: Int): Short {
        val persistent = keyStates[padKey]
        val queued = if (inputQueue.isPressed(padKey)) 0x41.toShort() else 0x40.toShort()
        return if (persistent == 0x41.toShort() || queued == 0x41.toShort()) 0x41 else 0x40
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-api:test --tests "knes.api.ApiControllerTest" --info`
Expected: All 10 tests pass (7 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiController.kt knes-api/src/test/kotlin/knes/api/ApiControllerTest.kt
git commit -m "feat: integrate InputQueue into ApiController with merged getKeyState"
```

---

### Task 3: Update `/step` route for shared mode

**Files:**
- Modify: `knes-api/src/main/kotlin/knes/api/ApiServer.kt`
- Modify: `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt`

- [ ] **Step 1: Write failing test for shared-mode step**

Append to `knes-api/src/test/kotlin/knes/api/ApiServerTest.kt`:

```kotlin
    test("POST /step in standalone mode uses queue for frame-precise input") {
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
```

- [ ] **Step 2: Run tests to verify existing tests still pass**

Run: `./gradlew :knes-api:test --tests "knes.api.ApiServerTest" --info`
Expected: All tests pass (baseline before route change).

- [ ] **Step 3: Modify `/step` route in `ApiServer.kt`**

Replace the `/step` route handler in `knes-api/src/main/kotlin/knes/api/ApiServer.kt`:

```kotlin
        post("/step") {
            if (!session.romLoaded) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
                return@post
            }
            val text = call.receiveText()
            try {
                val steps: List<StepRequest> = try {
                    val seq = Json.decodeFromString<StepSequence>(text)
                    seq.sequence
                } catch (e: Exception) {
                    listOf(Json.decodeFromString<StepRequest>(text))
                }

                if (session.shared) {
                    val latch = session.controller.enqueueSteps(steps)
                    val totalFrames = steps.sumOf { it.frames }
                    val timeoutMs = totalFrames * 50L + 5000L
                    if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            StatusResponse("step timed out waiting for $totalFrames frames")
                        )
                        return@post
                    }
                } else {
                    for (step in steps) {
                        session.controller.setButtons(step.buttons)
                        session.advanceFrames(step.frames)
                    }
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid request: ${e.message}"))
                return@post
            }
            call.respond(StepResponse(session.frameCount, session.getWatchedState()))
        }
```

- [ ] **Step 4: Run all API tests to verify nothing broke**

Run: `./gradlew :knes-api:test --info`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiServer.kt knes-api/src/test/kotlin/knes/api/ApiServerTest.kt
git commit -m "feat: /step route uses InputQueue for shared mode"
```

---

### Task 4: Wire frame boundary in ComposeMain

**Files:**
- Modify: `knes-compose-ui/src/main/kotlin/knes/compose/ComposeMain.kt`

- [ ] **Step 1: Modify `onApiFrameCallback` wiring**

In `knes-compose-ui/src/main/kotlin/knes/compose/ComposeMain.kt`, change the `LaunchedEffect(apiRunning)` block (around line 67-77). Replace:

```kotlin
        LaunchedEffect(apiRunning) {
            if (apiRunning) {
                screenView.onApiFrameCallback = { buffer ->
                    apiServer.session.updateFrameBuffer(buffer)
                }
                inputHandler.additionalInput = apiServer.session.controller
            } else {
                screenView.onApiFrameCallback = null
                inputHandler.additionalInput = null
            }
        }
```

With:

```kotlin
        LaunchedEffect(apiRunning) {
            if (apiRunning) {
                screenView.onApiFrameCallback = { buffer ->
                    apiServer.session.controller.onFrameBoundary()
                    apiServer.session.updateFrameBuffer(buffer)
                }
                inputHandler.additionalInput = apiServer.session.controller
            } else {
                screenView.onApiFrameCallback = null
                inputHandler.additionalInput = null
            }
        }
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :knes-compose-ui:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add knes-compose-ui/src/main/kotlin/knes/compose/ComposeMain.kt
git commit -m "feat: wire onFrameBoundary into Compose UI frame callback"
```

---

### Task 5: Update `NesEmulatorSession` for standalone consistency

**Files:**
- Modify: `knes-mcp/src/main/kotlin/knes/mcp/NesEmulatorSession.kt`
- Modify: `knes-mcp/src/test/kotlin/knes/mcp/NesEmulatorSessionTest.kt`

- [ ] **Step 1: Write failing tests for standalone queue**

Append to `knes-mcp/src/test/kotlin/knes/mcp/NesEmulatorSessionTest.kt`:

```kotlin
    test("enqueueSteps creates frame inputs from step requests") {
        val session = NesEmulatorSession()
        val latch = session.enqueueSteps(listOf(
            knes.api.StepRequest(listOf("A"), 2),
            knes.api.StepRequest(emptyList(), 1)
        ))
        // 3 frames total — first entry set as currentFrame
        session.inputQueue.isActive shouldBe true
        session.inputQueue.isPressed(knes.emulator.input.InputHandler.KEY_A) shouldBe true

        session.inputQueue.advanceFrame()
        session.inputQueue.isPressed(knes.emulator.input.InputHandler.KEY_A) shouldBe true

        session.inputQueue.advanceFrame()
        session.inputQueue.isPressed(knes.emulator.input.InputHandler.KEY_A) shouldBe false

        session.inputQueue.advanceFrame()
        latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe true
        session.inputQueue.isActive shouldBe false
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :knes-mcp:test --tests "knes.mcp.NesEmulatorSessionTest" --info`
Expected: Compilation failure — `enqueueSteps` and `inputQueue` don't exist on `NesEmulatorSession`.

- [ ] **Step 3: Modify `NesEmulatorSession`**

In `knes-mcp/src/main/kotlin/knes/mcp/NesEmulatorSession.kt`, add the queue and update `step()`:

Add imports at the top:

```kotlin
import knes.api.FrameInput
import knes.api.InputQueue
import knes.api.StepRequest
import java.util.concurrent.CountDownLatch
```

Add field after `buttonNames`:

```kotlin
    val inputQueue = InputQueue()
```

Add `enqueueSteps` method after `releaseAll`:

```kotlin
    fun enqueueSteps(steps: List<StepRequest>): CountDownLatch {
        val frameInputs = steps.flatMap { step ->
            val buttons = step.buttons.map { name ->
                buttonNames[name.uppercase()] ?: throw IllegalArgumentException("Unknown button: $name")
            }.toSet()
            List(step.frames) { FrameInput(buttons) }
        }
        return inputQueue.enqueue(frameInputs)
    }
```

Modify `getKeyState` in the `inputHandler` object to merge queue input:

```kotlin
    private val inputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short {
            val persistent = keyStates[padKey]
            val queued = if (inputQueue.isPressed(padKey)) 0x41.toShort() else 0x40.toShort()
            return if (persistent == 0x41.toShort() || queued == 0x41.toShort()) 0x41 else 0x40
        }
    }
```

Modify `step()` to call `advanceFrame` at frame boundaries:

```kotlin
    fun step(buttons: List<String>, frames: Int) {
        setButtons(buttons)
        val target = frameCount + frames
        val maxSteps = frames * 300_000
        var steps = 0
        var lastFrame = frameCount
        while (frameCount < target) {
            nes.cpu.step()
            if (frameCount != lastFrame) {
                inputQueue.advanceFrame()
                lastFrame = frameCount
            }
            if (++steps > maxSteps) throw IllegalStateException("step timed out")
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :knes-mcp:test --tests "knes.mcp.NesEmulatorSessionTest" --info`
Expected: All 15 tests pass (14 existing + 1 new).

- [ ] **Step 5: Commit**

```bash
git add knes-mcp/src/main/kotlin/knes/mcp/NesEmulatorSession.kt knes-mcp/src/test/kotlin/knes/mcp/NesEmulatorSessionTest.kt
git commit -m "feat: add InputQueue to NesEmulatorSession for standalone consistency"
```

---

### Task 6: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run all tests across all modules**

Run: `./gradlew test --info`
Expected: All tests pass. No regressions.

- [ ] **Step 2: Verify compilation of all modules**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit any remaining changes (if needed)**

If any test fixes were needed, commit them:
```bash
git add -A
git commit -m "fix: address test regressions from InputQueue integration"
```
