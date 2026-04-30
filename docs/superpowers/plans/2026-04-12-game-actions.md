# Pluggable Game Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pluggable Game Action system that lets game-specific automation scripts (like FF1 auto-battle) read RAM state and press buttons — nothing a real NES player couldn't do.

**Architecture:** `GameAction` interface in `knes-debug` alongside `GameProfile`, using the same static registry pattern. Actions can only read watched RAM and press buttons via an `ActionController` interface. FF1's `BattleFightAll` is the first action. API gets `GET/POST /profiles/{id}/actions` endpoints, MCP gets `list_actions` + `execute_action` tools.

**Tech Stack:** Kotlin, Ktor, Kotest, kotlinx.serialization. Follows existing kNES patterns: static registry, CountDownLatch coordination, manual JSON in knes-debug.

---

### Task 1: GameAction and ActionController interfaces

**Files:**
- Create: `knes-debug/src/main/kotlin/knes/debug/GameAction.kt`
- Test: `knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt`

The core contract. Actions can ONLY do what a real NES player can: observe the screen (read RAM) and press buttons on the controller.

- [ ] **Step 1: Write the failing test**

```kotlin
// knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt
package knes.debug

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GameActionTest : FunSpec({

    test("ActionResult captures success with message and state") {
        val result = ActionResult(
            success = true,
            message = "Battle won in 3 rounds",
            state = mapOf("char1_hpLow" to 25, "goldLow" to 200),
            screenshot = null
        )
        result.success shouldBe true
        result.message shouldBe "Battle won in 3 rounds"
        result.state["char1_hpLow"] shouldBe 25
    }

    test("ActionResult captures failure") {
        val result = ActionResult(
            success = false,
            message = "Not in battle",
            state = mapOf("screenState" to 0)
        )
        result.success shouldBe false
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: FAIL — class not found

- [ ] **Step 3: Write the interfaces and data classes**

```kotlin
// knes-debug/src/main/kotlin/knes/debug/GameAction.kt
package knes.debug

/**
 * A game-specific automation action that plays like a real NES player:
 * it can only read RAM state (like seeing the screen) and press buttons.
 * No memory writes, no save states, no cheats.
 */
interface GameAction {
    /** Unique action ID within its profile (e.g. "battle_fight_all") */
    val id: String
    /** Human-readable description */
    val description: String
    /** Profile this action belongs to (e.g. "ff1") */
    val profileId: String

    /**
     * Check if this action can execute right now based on current RAM state.
     * Example: FF1 battle actions check screenState == 0x68.
     */
    fun canExecute(state: Map<String, Int>): Boolean

    /**
     * Execute the action by reading state and pressing buttons.
     * The controller only exposes what a real player can do.
     */
    fun execute(controller: ActionController): ActionResult
}

/**
 * What an action is allowed to do — same as a human with a controller:
 * 1. Look at the screen (read RAM values)
 * 2. Press buttons (A, B, START, SELECT, UP, DOWN, LEFT, RIGHT)
 * 3. Wait (let frames pass)
 *
 * No memory writes. No save states. No frame-perfect tricks.
 */
interface ActionController {
    /** Read current watched RAM values (like looking at the screen) */
    fun readState(): Map<String, Int>

    /** Press a button N times with gaps (like tapping A repeatedly) */
    fun tap(button: String, count: Int = 1, pressFrames: Int = 5, gapFrames: Int = 40)

    /** Hold buttons for N frames (like holding RIGHT to walk) */
    fun step(buttons: List<String>, frames: Int)

    /** Wait without pressing anything (like watching an animation) */
    fun waitFrames(frames: Int)

    /** Take a screenshot of current frame */
    fun screenshot(): String?
}

/** Result of executing a game action */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val state: Map<String, Int> = emptyMap(),
    val screenshot: String? = null
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add knes-debug/src/main/kotlin/knes/debug/GameAction.kt knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt
git commit -m "feat(debug): add GameAction and ActionController interfaces

Actions can only read RAM and press buttons — nothing a real
NES player couldn't do. No memory writes, no cheats."
```

---

### Task 2: GameAction registry in GameProfile companion

**Files:**
- Modify: `knes-debug/src/main/kotlin/knes/debug/GameProfile.kt`
- Modify: `knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt`

Follow the same static registry pattern GameProfile uses for profiles.

- [ ] **Step 1: Write the failing test**

Add to `GameActionTest.kt`:

```kotlin
    test("register and retrieve actions by profile ID") {
        val action = object : GameAction {
            override val id = "test_action"
            override val description = "A test action"
            override val profileId = "test_profile"
            override fun canExecute(state: Map<String, Int>) = true
            override fun execute(controller: ActionController): ActionResult {
                return ActionResult(true, "done", controller.readState())
            }
        }

        GameAction.register(action)
        val actions = GameAction.listForProfile("test_profile")
        actions.size shouldBe 1
        actions[0].id shouldBe "test_action"
    }

    test("get specific action by profile and action ID") {
        val action = GameAction.get("test_profile", "test_action")
        action shouldNotBe null
        action!!.id shouldBe "test_action"
    }

    test("list returns empty for unknown profile") {
        val actions = GameAction.listForProfile("nonexistent")
        actions.size shouldBe 0
    }
```

Add import: `import io.kotest.matchers.shouldNotBe`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: FAIL — no `register`/`listForProfile`/`get` on GameAction

- [ ] **Step 3: Add static registry to GameAction**

Add to `GameAction.kt`, inside the `GameAction` interface:

```kotlin
    companion object {
        private val actions: MutableMap<String, MutableList<GameAction>> = mutableMapOf()

        fun register(action: GameAction) {
            actions.getOrPut(action.profileId) { mutableListOf() }.let { list ->
                list.removeAll { it.id == action.id }
                list.add(action)
            }
        }

        fun listForProfile(profileId: String): List<GameAction> {
            return actions[profileId]?.toList() ?: emptyList()
        }

        fun get(profileId: String, actionId: String): GameAction? {
            return actions[profileId]?.find { it.id == actionId }
        }

        fun listAll(): Map<String, List<GameAction>> {
            return actions.mapValues { it.value.toList() }
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add knes-debug/src/main/kotlin/knes/debug/GameAction.kt knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt
git commit -m "feat(debug): add GameAction static registry

Same pattern as GameProfile — register/list/get actions by profile ID."
```

---

### Task 3: FF1 BattleFightAll action

**Files:**
- Create: `knes-debug/src/main/kotlin/knes/debug/actions/ff1/BattleFightAll.kt`
- Modify: `knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt`

The first real action — all alive characters use FIGHT.

- [ ] **Step 1: Write the failing test with a mock controller**

Add to `GameActionTest.kt`:

```kotlin
    test("FF1 BattleFightAll: canExecute checks screenState") {
        val action = BattleFightAll()
        action.canExecute(mapOf("screenState" to 0x68)) shouldBe true
        action.canExecute(mapOf("screenState" to 0x00)) shouldBe false
        action.canExecute(mapOf("screenState" to 0x63)) shouldBe false
        action.canExecute(emptyMap()) shouldBe false
    }

    test("FF1 BattleFightAll: registered under ff1 profile") {
        val actions = GameAction.listForProfile("ff1")
        val battleAction = actions.find { it.id == "battle_fight_all" }
        battleAction shouldNotBe null
        battleAction!!.profileId shouldBe "ff1"
    }
```

Add import: `import knes.debug.actions.ff1.BattleFightAll`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: FAIL — class not found

- [ ] **Step 3: Implement BattleFightAll**

```kotlin
// knes-debug/src/main/kotlin/knes/debug/actions/ff1/BattleFightAll.kt
package knes.debug.actions.ff1

import knes.debug.ActionController
import knes.debug.ActionResult
import knes.debug.GameAction

/**
 * FF1 battle automation: all alive characters use FIGHT on the first
 * available enemy target. Loops until battle ends or safety limit hit.
 *
 * Plays exactly like a human mashing A on FIGHT — reads the screen
 * to know when to press buttons, nothing more.
 */
class BattleFightAll : GameAction {
    override val id = "battle_fight_all"
    override val description = "All alive characters use FIGHT until battle ends"
    override val profileId = "ff1"

    companion object {
        private const val SCREEN_STATE_BATTLE = 0x68
        private const val MAX_ROUNDS = 30
        private const val STATUS_DEAD_BIT = 1

        init {
            GameAction.register(BattleFightAll())
        }

        /** Call to trigger class loading and auto-registration */
        fun init() {}
    }

    override fun canExecute(state: Map<String, Int>): Boolean {
        return state["screenState"] == SCREEN_STATE_BATTLE
    }

    override fun execute(controller: ActionController): ActionResult {
        var rounds = 0

        while (rounds < MAX_ROUNDS) {
            val state = controller.readState()

            // Check if still in battle
            if (state["screenState"] != SCREEN_STATE_BATTLE) break

            // For each character slot: if alive, select FIGHT + confirm target
            // Menu starts on FIGHT, so A = select FIGHT, A = confirm target
            for (i in 1..4) {
                val status = state["char${i}_status"] ?: 0
                if (status and STATUS_DEAD_BIT != 0) continue // dead, skip

                controller.tap("A", count = 1, pressFrames = 5, gapFrames = 40) // FIGHT
                controller.tap("A", count = 1, pressFrames = 5, gapFrames = 40) // target
            }

            // Wait for battle round animation to play out
            controller.waitFrames(300)

            // Dismiss any battle text messages
            controller.tap("A", count = 4, pressFrames = 5, gapFrames = 40)

            rounds++
        }

        // Dismiss victory/result screens
        controller.tap("A", count = 10, pressFrames = 5, gapFrames = 40)
        controller.waitFrames(60)

        val finalState = controller.readState()
        val won = finalState["screenState"] != SCREEN_STATE_BATTLE

        return ActionResult(
            success = won,
            message = if (won) "Battle complete in $rounds rounds" else "Battle not finished after $MAX_ROUNDS rounds",
            state = finalState,
            screenshot = controller.screenshot()
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add knes-debug/src/main/kotlin/knes/debug/actions/ff1/BattleFightAll.kt knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt
git commit -m "feat(debug): add FF1 BattleFightAll game action

First pluggable action — all alive chars FIGHT until battle ends.
Only reads RAM and presses buttons, like a real player."
```

---

### Task 4: Action auto-registration on profile apply

**Files:**
- Create: `knes-debug/src/main/kotlin/knes/debug/actions/ActionRegistry.kt`

Actions need to be loaded when their profile is applied. Follow the same classloader trigger pattern.

- [ ] **Step 1: Write the test**

Add to `GameActionTest.kt`:

```kotlin
    test("ActionRegistry.ensureLoaded triggers FF1 action registration") {
        ActionRegistry.ensureLoaded("ff1")
        val actions = GameAction.listForProfile("ff1")
        actions.any { it.id == "battle_fight_all" } shouldBe true
    }

    test("ActionRegistry.ensureLoaded is safe for unknown profiles") {
        ActionRegistry.ensureLoaded("unknown_game")
        // Should not throw
    }
```

Add import: `import knes.debug.actions.ActionRegistry`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: FAIL — ActionRegistry not found

- [ ] **Step 3: Implement ActionRegistry**

```kotlin
// knes-debug/src/main/kotlin/knes/debug/actions/ActionRegistry.kt
package knes.debug.actions

import knes.debug.actions.ff1.BattleFightAll

/**
 * Triggers class loading for game-specific actions.
 * Each game's action classes self-register via companion init blocks.
 * Call ensureLoaded() when a profile is applied.
 */
object ActionRegistry {
    private val loaded = mutableSetOf<String>()

    fun ensureLoaded(profileId: String) {
        if (profileId in loaded) return
        loaded.add(profileId)

        when (profileId) {
            "ff1" -> loadFF1Actions()
        }
    }

    private fun loadFF1Actions() {
        BattleFightAll.init()
        // Add more FF1 actions here as they're created
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add knes-debug/src/main/kotlin/knes/debug/actions/ActionRegistry.kt knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt
git commit -m "feat(debug): add ActionRegistry for auto-loading game actions

Triggers class loading when a profile is applied.
New games add their actions to the when() block."
```

---

### Task 5: ActionController implementation in knes-api

**Files:**
- Create: `knes-api/src/main/kotlin/knes/api/SessionActionController.kt`
- Test: `knes-api/src/test/kotlin/knes/api/SessionActionControllerTest.kt`

The real implementation that bridges GameAction's interface to EmulatorSession.

- [ ] **Step 1: Write the test**

```kotlin
// knes-api/src/test/kotlin/knes/api/SessionActionControllerTest.kt
package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SessionActionControllerTest : FunSpec({

    test("SessionActionController implements ActionController") {
        val session = EmulatorSession()
        val controller = SessionActionController(session)
        // Should compile and create without error
        controller shouldNotBe null
    }

    test("readState returns watched addresses") {
        val session = EmulatorSession()
        session.setWatchedAddresses(mapOf("test" to 0x0000))
        val controller = SessionActionController(session)
        val state = controller.readState()
        state.containsKey("test") shouldBe true
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :knes-api:test --tests "knes.api.SessionActionControllerTest" --info`
Expected: FAIL — class not found

- [ ] **Step 3: Implement SessionActionController**

```kotlin
// knes-api/src/main/kotlin/knes/api/SessionActionController.kt
package knes.api

import knes.debug.ActionController

/**
 * Bridges the ActionController interface to a real EmulatorSession.
 * All operations go through the same input queue as manual play —
 * no shortcuts, no cheats, just button presses and screen reads.
 */
class SessionActionController(
    private val session: EmulatorSession
) : ActionController {

    override fun readState(): Map<String, Int> {
        return session.getWatchedState()
    }

    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int) {
        val steps = (1..count).flatMap {
            listOf(
                StepRequest(buttons = listOf(button), frames = pressFrames),
                StepRequest(buttons = emptyList(), frames = gapFrames)
            )
        }
        executeSteps(steps)
    }

    override fun step(buttons: List<String>, frames: Int) {
        executeSteps(listOf(StepRequest(buttons = buttons, frames = frames)))
    }

    override fun waitFrames(frames: Int) {
        executeSteps(listOf(StepRequest(buttons = emptyList(), frames = frames)))
    }

    override fun screenshot(): String? {
        return try {
            session.getScreenBase64()
        } catch (_: Exception) {
            null
        }
    }

    private fun executeSteps(steps: List<StepRequest>) {
        if (session.isSharedMode) {
            val latch = session.controller.enqueueSteps(steps)
            latch.await()
        } else {
            for (step in steps) {
                session.controller.setButtons(step.buttons)
                session.advanceFrames(step.frames)
            }
            session.controller.releaseAll()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :knes-api:test --tests "knes.api.SessionActionControllerTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/SessionActionController.kt knes-api/src/test/kotlin/knes/api/SessionActionControllerTest.kt
git commit -m "feat(api): add SessionActionController bridging actions to emulator

Implements ActionController by delegating to EmulatorSession.
Same input queue as manual play — no shortcuts."
```

---

### Task 6: REST API endpoints for actions

**Files:**
- Modify: `knes-api/src/main/kotlin/knes/api/ApiServer.kt`

Add endpoints to list and execute actions for the active profile.

- [ ] **Step 1: Add action data classes**

Add to `ApiServer.kt` near the other data classes:

```kotlin
@Serializable
data class ActionInfo(
    val id: String,
    val description: String,
    val canExecute: Boolean
)

@Serializable
data class ActionListResponse(
    val profileId: String,
    val actions: List<ActionInfo>
)

@Serializable
data class ActionExecuteRequest(
    val screenshot: Boolean = true
)

@Serializable
data class ActionExecuteResponse(
    val success: Boolean,
    val message: String,
    val state: Map<String, Int> = emptyMap(),
    val screenshot: String? = null
)
```

- [ ] **Step 2: Add the action endpoints**

Add to `ApiServer.kt` inside `configureRoutes()`, after the profile endpoints:

```kotlin
// List available actions for a profile
get("/profiles/{id}/actions") {
    val id = call.parameters["id"]
        ?: return@get call.respond(HttpStatusCode.BadRequest, StatusResponse("missing profile id"))

    knes.debug.actions.ActionRegistry.ensureLoaded(id)
    val actions = knes.debug.GameAction.listForProfile(id)
    val state = if (session.romLoaded) session.getWatchedState() else emptyMap()

    call.respond(ActionListResponse(
        profileId = id,
        actions = actions.map { ActionInfo(it.id, it.description, it.canExecute(state)) }
    ))
}

// Execute a specific action
post("/profiles/{id}/actions/{actionId}") {
    val profileId = call.parameters["id"]
        ?: return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("missing profile id"))
    val actionId = call.parameters["actionId"]
        ?: return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("missing action id"))

    if (!session.romLoaded) {
        return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("no ROM loaded"))
    }

    knes.debug.actions.ActionRegistry.ensureLoaded(profileId)
    val action = knes.debug.GameAction.get(profileId, actionId)
        ?: return@post call.respond(HttpStatusCode.NotFound, StatusResponse("action '$actionId' not found for profile '$profileId'"))

    val state = session.getWatchedState()
    if (!action.canExecute(state)) {
        return@post call.respond(HttpStatusCode.BadRequest,
            StatusResponse("action '$actionId' cannot execute in current state"))
    }

    val controller = SessionActionController(session)
    val result = action.execute(controller)

    call.respond(ActionExecuteResponse(
        success = result.success,
        message = result.message,
        state = result.state,
        screenshot = result.screenshot
    ))
}
```

- [ ] **Step 3: Run existing tests to check nothing broke**

Run: `./gradlew :knes-api:test --info`
Expected: All existing tests PASS

- [ ] **Step 4: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiServer.kt
git commit -m "feat(api): add REST endpoints for game actions

GET /profiles/{id}/actions — list available actions
POST /profiles/{id}/actions/{actionId} — execute an action"
```

---

### Task 7: MCP tools for actions

**Files:**
- Modify: `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`

Add `list_actions` and `execute_action` MCP tools.

- [ ] **Step 1: Add list_actions tool**

Add to `McpServer.kt` after the `apply_profile` tool:

```kotlin
server.addTool(
    name = "list_actions",
    description = "List available game actions for a profile. Actions are game-specific automation scripts that play like a real NES player — they read the screen and press buttons.",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("profile_id") {
                put("type", "string")
                put("description", "Profile ID (e.g. 'ff1')")
            }
        },
        required = listOf("profile_id")
    )
) { request ->
    val profileId = request.arguments?.get("profile_id")?.jsonPrimitive?.content
        ?: return@addTool CallToolResult(
            content = listOf(TextContent("Missing profile_id")), isError = true
        )

    val resp = api.get("/profiles/$profileId/actions")
    if (resp.ok) {
        CallToolResult(content = listOf(TextContent(resp.body)))
    } else {
        CallToolResult(
            content = listOf(TextContent("list_actions failed: ${resp.body}")), isError = true
        )
    }
}
```

- [ ] **Step 2: Add execute_action tool**

```kotlin
server.addTool(
    name = "execute_action",
    description = "Execute a game action. Actions play like a real NES player: they read RAM state and press buttons. No memory writes, no cheats. Example: execute_action('ff1', 'battle_fight_all') auto-fights an FF1 battle.",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("profile_id") {
                put("type", "string")
                put("description", "Profile ID (e.g. 'ff1')")
            }
            putJsonObject("action_id") {
                put("type", "string")
                put("description", "Action ID (e.g. 'battle_fight_all')")
            }
            putJsonObject("screenshot") {
                put("type", "boolean")
                put("description", "Include screenshot in result (default: true)")
            }
        },
        required = listOf("profile_id", "action_id")
    )
) { request ->
    val profileId = request.arguments?.get("profile_id")?.jsonPrimitive?.content
        ?: return@addTool CallToolResult(
            content = listOf(TextContent("Missing profile_id")), isError = true
        )
    val actionId = request.arguments?.get("action_id")?.jsonPrimitive?.content
        ?: return@addTool CallToolResult(
            content = listOf(TextContent("Missing action_id")), isError = true
        )
    val screenshot = request.arguments?.get("screenshot")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

    val resp = api.postJson(
        "/profiles/$profileId/actions/$actionId",
        """{"screenshot":$screenshot}"""
    )
    if (resp.ok) {
        val content = mutableListOf<ContentBlock>(TextContent(resp.body))
        if (screenshot) {
            val imageMatch = Regex(""""screenshot"\s*:\s*"([^"]+)"""").find(resp.body)
            if (imageMatch != null) {
                content.add(ImageContent(data = imageMatch.groupValues[1], mimeType = "image/png"))
            }
        }
        CallToolResult(content = content)
    } else {
        CallToolResult(
            content = listOf(TextContent("execute_action failed: ${resp.body}")), isError = true
        )
    }
}
```

- [ ] **Step 3: Run MCP build to verify compilation**

Run: `./gradlew :knes-mcp:classes --info`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt
git commit -m "feat(mcp): add list_actions and execute_action tools

MCP tools for the pluggable game action system.
execute_action runs game-specific automation that plays like a real player."
```

---

### Task 8: Wire up action loading on profile apply

**Files:**
- Modify: `knes-api/src/main/kotlin/knes/api/ApiServer.kt`

When a profile is applied, also ensure its actions are loaded.

- [ ] **Step 1: Add ActionRegistry call to profile apply endpoint**

In `ApiServer.kt`, find the `post("/profiles/{id}/apply")` handler and add after `session.setWatchedAddresses(...)`:

```kotlin
knes.debug.actions.ActionRegistry.ensureLoaded(id)
```

- [ ] **Step 2: Run full build**

Run: `./gradlew build --info`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 3: Commit**

```bash
git add knes-api/src/main/kotlin/knes/api/ApiServer.kt
git commit -m "feat(api): auto-load game actions when profile is applied

ActionRegistry.ensureLoaded() triggers on /profiles/{id}/apply."
```

---

### Task 9: Integration test — full action flow

**Files:**
- Modify: `knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt`

Test the complete flow with a mock controller.

- [ ] **Step 1: Add integration test with mock controller**

```kotlin
    test("BattleFightAll executes correctly with mock controller") {
        var tapCount = 0
        var waitCount = 0
        var stateCallCount = 0

        val mockController = object : ActionController {
            override fun readState(): Map<String, Int> {
                stateCallCount++
                // First call: in battle. After a few rounds: battle over.
                return if (stateCallCount <= 3) {
                    mapOf(
                        "screenState" to 0x68,
                        "char1_status" to 0,
                        "char2_status" to 0,
                        "char3_status" to 0,
                        "char4_status" to 0
                    )
                } else {
                    mapOf("screenState" to 0x63) // map after battle
                }
            }

            override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int) {
                tapCount += count
            }

            override fun step(buttons: List<String>, frames: Int) {}
            override fun waitFrames(frames: Int) { waitCount++ }
            override fun screenshot(): String? = null
        }

        val action = BattleFightAll()
        val result = action.execute(mockController)

        result.success shouldBe true
        result.message shouldContain "Battle complete"
        tapCount shouldBeGreaterThan 0
        waitCount shouldBeGreaterThan 0
    }
```

Add imports:
```kotlin
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
```

- [ ] **Step 2: Run test**

Run: `./gradlew :knes-debug:test --tests "knes.debug.GameActionTest" --info`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add knes-debug/src/test/kotlin/knes/debug/GameActionTest.kt
git commit -m "test(debug): add integration test for BattleFightAll with mock controller"
```

---

### Task 10: Full build verification

- [ ] **Step 1: Run complete build with all tests**

Run: `./gradlew build --info`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Final commit with any fixes**

If any issues found, fix and commit. Otherwise, tag the feature as complete.
