# FF1 Koog Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Kotlin-native autonomous FF1-playing agent (Koog Advisor/Executor over Anthropic) that defeats Garland in-process, plus the shared `EmulatorToolset` it requires.

**Architecture:** Two-step refactor + new module. (1) Extract today's tool surface (today scattered across `ApiServer` route handlers + `McpServer` HTTP bridge) into a single annotated `EmulatorToolset` in a new `knes-agent-tools` module; thin out `ApiServer` and replace `McpServer`'s HTTP path with a direct call into the same toolset. (2) Add `knes-agent` module: Koog `AIAgent` executor (Sonnet, `reActStrategy`) calls toolset directly and consults a Koog `AIAgent` advisor (Opus, single-shot) registered as a tool via `createAgentTool`. RAM-driven runtime owns success detection, watchdog escalation, budget caps, and a JSONL trace log.

**Tech Stack:** Kotlin 1.9+, Gradle, Koog (`ai.koog:agents-core`, `ai.koog:prompt-executor-anthropic-client`), MCP Kotlin SDK, Ktor (existing), kotlinx.serialization, JUnit 5.

**Spec:** [`docs/superpowers/specs/2026-04-30-ff1-koog-agent-design.md`](../specs/2026-04-30-ff1-koog-agent-design.md)

---

## Phase 1 — Extract `EmulatorToolset` (parity refactor)

End-of-phase property: every existing kNES MCP and REST integration test still passes; `McpServer` no longer requires `knes-api` to be running for in-process mode.

### Task 1.1: Create `knes-agent-tools` Gradle module

**Files:**
- Create: `knes-agent-tools/build.gradle`
- Modify: `settings.gradle`

- [ ] **Step 1: Add module include**

In `settings.gradle`, append:

```groovy
include 'knes-agent-tools'
```

- [ ] **Step 2: Create build file**

Create `knes-agent-tools/build.gradle`:

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
    id 'org.jetbrains.kotlin.plugin.serialization'
}

dependencies {
    implementation project(':knes-emulator')
    implementation project(':knes-controllers')
    implementation project(':knes-debug')
    implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
}

test { useJUnitPlatform() }
```

- [ ] **Step 3: Verify the empty module builds**

Run: `./gradlew :knes-agent-tools:build`
Expected: `BUILD SUCCESSFUL` (no source yet — Kotlin happily compiles an empty module).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle knes-agent-tools/build.gradle
git commit -m "feat(agent-tools): scaffold knes-agent-tools module"
```

---

### Task 1.2: Define typed result DTOs

**Files:**
- Create: `knes-agent-tools/src/main/kotlin/knes/agent/tools/results/Results.kt`

These are the typed return values shared by every tool entry-point. They must round-trip through both Koog `Tool` results and MCP `CallToolResult`/REST JSON, so use `@Serializable`.

- [ ] **Step 1: Write the file**

Create `knes-agent-tools/src/main/kotlin/knes/agent/tools/results/Results.kt`:

```kotlin
package knes.agent.tools.results

import kotlinx.serialization.Serializable

@Serializable
data class StatusResult(val ok: Boolean, val message: String = "")

@Serializable
data class StepEntry(val buttons: List<String>, val frames: Int)

@Serializable
data class StepResult(
    val frame: Int,
    val ram: Map<String, Int>,
    val heldButtons: List<String>,
    /** Base64-encoded PNG, present iff the caller requested a screenshot. */
    val screenshot: String? = null,
)

@Serializable
data class StateSnapshot(
    val frame: Int,
    val ram: Map<String, Int>,
    val cpu: Map<String, Int>,
    val heldButtons: List<String>,
)

@Serializable
data class ScreenPng(val base64: String, val width: Int = 256, val height: Int = 240)

@Serializable
data class ProfileSummary(val id: String, val name: String, val description: String)

@Serializable
data class ActionDescriptor(
    val id: String,
    val profileId: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class ActionToolResult(val ok: Boolean, val message: String, val data: Map<String, String> = emptyMap())
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent-tools:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent-tools/src/main/kotlin/knes/agent/tools/results/Results.kt
git commit -m "feat(agent-tools): typed result DTOs"
```

---

### Task 1.3: Implement `EmulatorToolset` (logic only, no annotations yet)

**Files:**
- Create: `knes-agent-tools/src/main/kotlin/knes/agent/tools/EmulatorToolset.kt`

We add the Koog `@Tool` annotations in Phase 2 (Task 2.3), once `agents-core` is on the classpath. For now we want a plain class that `ApiServer` and `McpServer` can delegate to.

- [ ] **Step 1: Write the toolset (no Koog imports yet)**

Create `knes-agent-tools/src/main/kotlin/knes/agent/tools/EmulatorToolset.kt`:

```kotlin
package knes.agent.tools

import knes.agent.tools.results.*
import knes.api.ApiController
import knes.api.EmulatorSession
import knes.api.FrameInput
import knes.api.StepRequest
import knes.debug.GameAction
import knes.debug.GameProfile
import knes.debug.actions.ActionRegistry

/**
 * Single source of truth for kNES tool surface.
 * Consumed in-process by:
 *   - `knes-api` (Ktor handlers delegate here)
 *   - `knes-mcp` (MCP server delegates here, no HTTP)
 *   - `knes-agent` (Koog ToolRegistry registers this directly)
 */
class EmulatorToolset(
    private val session: EmulatorSession,
    private val controller: ApiController = session.controller,
) {
    fun loadRom(path: String): StatusResult {
        val ok = session.loadRom(path)
        return StatusResult(ok, if (ok) "ROM loaded: $path" else "Failed to load ROM: $path")
    }

    fun reset(): StatusResult {
        session.reset()
        return StatusResult(true, "reset")
    }

    fun step(buttons: List<String>, frames: Int = 1, screenshot: Boolean = false): StepResult {
        require(frames in 1..600) { "frames must be 1..600, got $frames" }
        val request = StepRequest(buttons = buttons, frames = frames)
        controller.enqueueSteps(listOf(request)).await()
        return readStepResult(screenshot)
    }

    fun tap(
        button: String,
        count: Int = 1,
        pressFrames: Int = 5,
        gapFrames: Int = 15,
        screenshot: Boolean = false,
    ): StepResult {
        require(count in 1..50) { "count must be 1..50, got $count" }
        val steps = (0 until count).flatMap {
            listOf(
                StepRequest(buttons = listOf(button), frames = pressFrames),
                StepRequest(buttons = emptyList(), frames = gapFrames),
            )
        }
        controller.enqueueSteps(steps).await()
        return readStepResult(screenshot)
    }

    fun sequence(steps: List<StepEntry>, screenshot: Boolean = false): StepResult {
        require(steps.isNotEmpty()) { "sequence requires at least one entry" }
        controller.enqueueSteps(steps.map { StepRequest(it.buttons, it.frames) }).await()
        return readStepResult(screenshot)
    }

    fun getState(): StateSnapshot = StateSnapshot(
        frame = session.frameCount,
        ram = session.readWatchedRam(),
        cpu = session.readCpuRegs(),
        heldButtons = controller.getHeldButtons(),
    )

    fun getScreen(): ScreenPng = ScreenPng(base64 = session.screenshotBase64Png())

    fun applyProfile(id: String): StatusResult {
        val profile = GameProfile.get(id) ?: return StatusResult(false, "Unknown profile: $id")
        session.applyProfile(profile)
        ActionRegistry.ensureLoaded(id)
        return StatusResult(true, "applied: $id")
    }

    fun listProfiles(): List<ProfileSummary> =
        GameProfile.all().map { ProfileSummary(it.id, it.name, it.description) }

    fun listActions(profileId: String? = null): List<ActionDescriptor> {
        val map = if (profileId != null) {
            mapOf(profileId to GameAction.listForProfile(profileId).also { ActionRegistry.ensureLoaded(profileId) })
        } else GameAction.listAll()
        return map.flatMap { (pid, actions) ->
            actions.map { ActionDescriptor(it.id, pid, it.description, it.parameters) }
        }
    }

    fun executeAction(profileId: String, actionId: String, args: Map<String, String> = emptyMap()): ActionToolResult {
        ActionRegistry.ensureLoaded(profileId)
        val action = GameAction.get(profileId, actionId)
            ?: return ActionToolResult(false, "Action not found: $profileId/$actionId")
        val result = action.execute(session.actionController(args))
        return ActionToolResult(result.success, result.message, result.data.mapValues { it.value.toString() })
    }

    fun press(buttons: List<String>): StatusResult {
        controller.setButtons(buttons)
        return StatusResult(true, "held: ${controller.getHeldButtons()}")
    }

    fun release(buttons: List<String>): StatusResult {
        if (buttons.isEmpty()) controller.releaseAll()
        else buttons.forEach { controller.releaseButton(controller.resolveButton(it)) }
        return StatusResult(true, "released")
    }

    private fun readStepResult(screenshot: Boolean): StepResult = StepResult(
        frame = session.frameCount,
        ram = session.readWatchedRam(),
        heldButtons = controller.getHeldButtons(),
        screenshot = if (screenshot) session.screenshotBase64Png() else null,
    )
}
```

- [ ] **Step 2: Verify compile reveals which `EmulatorSession` helpers we still need**

Run: `./gradlew :knes-agent-tools:compileKotlin`
Expected: errors pointing at `session.loadRom`, `session.reset`, `session.readWatchedRam`, `session.readCpuRegs`, `session.screenshotBase64Png`, `session.applyProfile`, `session.actionController`. These methods are equivalent to what `ApiServer.kt` and `McpServer.kt` currently inline; the next sub-step is to expose them on `EmulatorSession`.

- [ ] **Step 3: Promote helpers on `EmulatorSession`**

Read `knes-api/src/main/kotlin/knes/api/EmulatorSession.kt` and `knes-api/src/main/kotlin/knes/api/ApiServer.kt`. For each missing helper, lift the corresponding inline route-handler logic into a method on `EmulatorSession`. Keep return types primitive: `Map<String, Int>` for RAM, `String` for base64 PNG, `Boolean` for `loadRom`. Keep `ApiServer` calling-points unchanged for now (refactored in Task 1.4).

- [ ] **Step 4: Recompile**

Run: `./gradlew :knes-api:compileKotlin :knes-agent-tools:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add knes-agent-tools/src/main/kotlin knes-api/src/main/kotlin/knes/api/EmulatorSession.kt
git commit -m "feat(agent-tools): EmulatorToolset over EmulatorSession"
```

---

### Task 1.4: Refactor `ApiServer` to delegate to `EmulatorToolset`

**Files:**
- Modify: `knes-api/build.gradle` (add `:knes-agent-tools` dep)
- Modify: `knes-api/src/main/kotlin/knes/api/ApiServer.kt`

- [ ] **Step 1: Add module dependency**

Edit `knes-api/build.gradle`, add to `dependencies`:

```groovy
implementation project(':knes-agent-tools')
```

- [ ] **Step 2: Wire toolset and replace handlers**

In `ApiServer.kt`, instantiate the toolset alongside the existing `EmulatorSession`, then replace each route body to call `toolset.x(...)` and serialize the typed result. Routes to migrate (with current line ranges as starting reference): `/rom` (64), `/reset` (78), `/step` (83), `/tap` (129), `/screen` (171), `/screen/base64` (179), `/state` (187), `/watch` (206), `/profiles` (215), `/profiles/{id}` (225), `/profiles/{id}/apply` (235), `/profiles/{id}/actions` (247), `/profiles/{id}/actions/{actionId}` (261). Each route body shrinks to ~3 lines.

Pattern for `POST /step`:

```kotlin
post("/step") {
    val req = call.receive<StepRequest>()
    val result = toolset.step(req.buttons, req.frames, req.screenshot ?: false)
    call.respond(result)
}
```

- [ ] **Step 3: Compile and run existing API tests**

Run: `./gradlew :knes-api:test`
Expected: same green/yellow as before this task. If a test fails, the refactor changed observable behavior — investigate before continuing.

- [ ] **Step 4: Commit**

```bash
git add knes-api/build.gradle knes-api/src/main/kotlin/knes/api/ApiServer.kt
git commit -m "refactor(api): delegate route handlers to EmulatorToolset"
```

---

### Task 1.5: Refactor `McpServer` to delegate to `EmulatorToolset` (in-process)

**Files:**
- Modify: `knes-mcp/build.gradle`
- Modify: `knes-mcp/src/main/kotlin/knes/mcp/McpServer.kt`
- Optional new: `knes-mcp/src/main/kotlin/knes/mcp/RemoteRestBridge.kt` (extracted, kept for legacy `--remote` mode)

- [ ] **Step 1: Add module dependency**

Edit `knes-mcp/build.gradle`, add `implementation project(':knes-agent-tools')` and `implementation project(':knes-api')` (we still need `EmulatorSession` to construct the toolset when running standalone).

- [ ] **Step 2: Move existing REST-bridge body into `RemoteRestBridge.kt`**

The current `createMcpServer()` body (uses `RestApiClient`) becomes `createRemoteMcpServer()` in `RemoteRestBridge.kt`. No logic change.

- [ ] **Step 3: Write the new in-process `createMcpServer`**

Replace `createMcpServer()` so that, by default, it constructs an `EmulatorSession` (standalone, headless), an `EmulatorToolset(session)`, and registers each MCP tool by hand-mapping its `request.arguments` JSON to the toolset method (mirror the existing schemas one-to-one). For each tool the body becomes 5–10 lines: parse args → `toolset.x(...)` → wrap result as `CallToolResult` (text + image content if `screenshot=true`).

Tool list (matches `docs/ff1-system-prompt.md`): `load_rom`, `reset`, `step`, `tap`, `sequence`, `get_state`, `get_screen`, `apply_profile`, `list_profiles`, `list_actions`, `execute_action`, `press`, `release`.

- [ ] **Step 4: Add `--remote` CLI flag**

In `knes-mcp/src/main/kotlin/knes/mcp/Main.kt` (or equivalent entry point), add:

```kotlin
val server = if (args.contains("--remote")) createRemoteMcpServer() else createMcpServer()
```

This preserves the legacy "MCP talks to a separate kNES REST process" workflow.

- [ ] **Step 5: Compile and run MCP tests**

Run: `./gradlew :knes-mcp:test`
Expected: same green/yellow as before. Tests that hit `RestApiClient` may need updates to point at `RemoteRestBridge` or to use the in-process default; minimal edits, no semantic changes.

- [ ] **Step 6: Commit**

```bash
git add knes-mcp/
git commit -m "refactor(mcp): delegate to EmulatorToolset in-process; --remote retains REST bridge"
```

---

### Task 1.6: Phase 1 sanity sweep

- [ ] **Step 1: Whole-tree build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Any newly broken tests are regressions from Tasks 1.4 / 1.5 — fix before moving on.

- [ ] **Step 2: Manual smoke**

```bash
./gradlew :knes-mcp:run &  # in-process by default now
# in another shell, send the MCP listTools request and confirm 13 tools come back
```

- [ ] **Step 3: Commit any fixes**

```bash
git add -A && git commit -m "fix: Phase 1 followups"
```

---

## Phase 2 — Koog plumbing in `knes-agent`

End-of-phase property: a tiny program in `knes-agent` calls Anthropic via Koog, runs `reActStrategy` against the real `EmulatorToolset` for one step, and exits cleanly.

### Task 2.1: Create `knes-agent` Gradle module

**Files:**
- Create: `knes-agent/build.gradle`
- Modify: `settings.gradle`

- [ ] **Step 1: Add module include**

Append to `settings.gradle`:

```groovy
include 'knes-agent'
```

- [ ] **Step 2: Build file**

Create `knes-agent/build.gradle`:

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
    id 'org.jetbrains.kotlin.plugin.serialization'
    id 'application'
}

application {
    mainClass = 'knes.agent.MainKt'
}

dependencies {
    implementation project(':knes-emulator')
    implementation project(':knes-controllers')
    implementation project(':knes-debug')
    implementation project(':knes-agent-tools')
    implementation project(':knes-api')           // for EmulatorSession (constructor-only)

    // Koog — pin to a specific release at first compile; update if breaking.
    implementation 'ai.koog:agents-core:0.5.1'
    implementation 'ai.koog:agents-mcp:0.5.1'
    implementation 'ai.koog:prompt-executor-anthropic-client:0.5.1'

    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3'
    implementation 'ch.qos.logback:logback-classic:1.5.6'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1'
}

test { useJUnitPlatform() }
```

- [ ] **Step 3: Verify dependency resolution**

Run: `./gradlew :knes-agent:dependencies --configuration runtimeClasspath`
Expected: Koog 0.5.1 artifacts resolve. If they don't, adjust the version (run `./gradlew :knes-agent:dependencies` and check what's available; the spec pins 0.5.1 because that's the version Context7 surfaced; bump if necessary and note the new version in this task's commit message).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle knes-agent/build.gradle
git commit -m "feat(agent): scaffold knes-agent module with Koog deps"
```

---

### Task 2.2: Anthropic smoke test (one-liner)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/AnthropicSmokeTest.kt`

- [ ] **Step 1: Write the test**

Create `knes-agent/src/test/kotlin/knes/agent/AnthropicSmokeTest.kt`:

```kotlin
package knes.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class AnthropicSmokeTest {
    @Test
    fun `roundtrips a trivial prompt`() = runTest {
        val key = System.getenv("ANTHROPIC_API_KEY")
        assumeTrue(key != null, "ANTHROPIC_API_KEY not set; skipping live test")

        val client = AnthropicLLMClient(apiKey = key!!)
        val response = client.execute(
            prompt = prompt("smoke") {
                system("Reply with the single word PONG, nothing else.")
                user("ping")
            },
            model = AnthropicModels.Sonnet_4_5,
        )
        assertTrue(response.toString().contains("PONG", ignoreCase = true))
    }
}
```

- [ ] **Step 2: Run with key**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:test --tests AnthropicSmokeTest`
Expected: PASS. If you don't have a key set, the test self-skips via `assumeTrue`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/AnthropicSmokeTest.kt
git commit -m "test(agent): smoke test live Anthropic call via Koog"
```

---

### Task 2.3: Koog `@Tool` annotations on `EmulatorToolset`

**Files:**
- Modify: `knes-agent-tools/build.gradle` (add Koog `agents-core` dep)
- Modify: `knes-agent-tools/src/main/kotlin/knes/agent/tools/EmulatorToolset.kt`

We delayed adding annotations until Koog was on the classpath; add them now.

- [ ] **Step 1: Add Koog dep to agent-tools**

Append to `knes-agent-tools/build.gradle`:

```groovy
implementation 'ai.koog:agents-core:0.5.1'
```

- [ ] **Step 2: Annotate**

In `EmulatorToolset.kt`, make the class implement `ToolSet`, add `@LLMDescription` to the class and `@Tool @LLMDescription("…")` to every public method. The descriptions should match the wording in `docs/ff1-system-prompt.md` so the existing FF1 system prompt remains coherent.

```kotlin
import ai.koog.agents.core.tools.ToolSet
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription

@LLMDescription("Tools for controlling the kNES emulator: input, screenshots, RAM state, profiles, and registered game actions.")
class EmulatorToolset(...) : ToolSet {
    @Tool @LLMDescription("Load a NES ROM by absolute path.")
    fun loadRom(path: String): StatusResult = ...
    // ...same for every tool, copy descriptions from McpServer.kt
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent-tools:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If a Koog version-skew error fires (e.g. annotation package moved), update the import to whatever the resolved Koog version provides.

- [ ] **Step 4: Commit**

```bash
git add knes-agent-tools/
git commit -m "feat(agent-tools): annotate EmulatorToolset as Koog ToolSet"
```

---

### Task 2.4: ReAct + ToolSet smoke test

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/ReactSmokeTest.kt`

Goal: prove that a Koog `AIAgent` with `reActStrategy` can call `EmulatorToolset.getState()` against a real emulator and return.

- [ ] **Step 1: Write the test**

Create `knes-agent/src/test/kotlin/knes/agent/ReactSmokeTest.kt`:

```kotlin
package knes.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentStrategies
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.simpleAnthropicExecutor
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class ReactSmokeTest {
    @Test
    fun `agent calls getState once and returns`() = runTest {
        val key = System.getenv("ANTHROPIC_API_KEY")
        assumeTrue(key != null, "ANTHROPIC_API_KEY not set")

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        val registry = ToolRegistry { tools(toolset) }

        val agent = AIAgent(
            promptExecutor = simpleAnthropicExecutor(key!!),
            llmModel = AnthropicModels.Sonnet_4_5,
            toolRegistry = registry,
            graphStrategy = AIAgentStrategies.reActStrategy(maxIterations = 4, name = "smoke"),
            systemPrompt = "Call get_state exactly once, then reply DONE.",
        )

        val result = agent.run("Report the current frame count.")
        assertNotNull(result)
    }
}
```

- [ ] **Step 2: Run**

Run: `ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:test --tests ReactSmokeTest`
Expected: PASS, with the test logs showing one tool call to `get_state`. If Koog API names differ in the resolved version (`graphStrategy` vs `strategy`, etc.), adjust the call site — the test must succeed before continuing.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/ReactSmokeTest.kt
git commit -m "test(agent): smoke test reActStrategy + EmulatorToolset"
```

---

## Phase 3 — Perception layer

### Task 3.1: `FfPhase` and `RamObserver`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/FfPhase.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/RamObserverTest.kt`

Source for RAM addresses: `knes-debug/src/main/resources/profiles/ff1.json`. Key addresses we use:

| Field | Address | Meaning |
|---|---|---|
| `screenState` | `0x0081` | `0x68` = battle, `0x63` = post-battle map |
| `enemyMainType` | `0x6BC9` | enemy id in current battle |
| `enemy1_dead` | `0x6BD9` | non-zero ⇒ Garland slot down |
| `enemy1_hpLow/High` | `0x6BD5/6` | Garland HP |
| `char[1-4]_status` | `0x6101 / 6141 / 6181 / 61C1` | bit0 = dead |
| `worldX / worldY` | `0x0027 / 0x0028` | overworld tile coords |

- [ ] **Step 1: Write `FfPhase`**

Create `knes-agent/src/main/kotlin/knes/agent/perception/FfPhase.kt`:

```kotlin
package knes.agent.perception

sealed interface FfPhase {
    object Boot : FfPhase
    object TitleOrMenu : FfPhase
    data class Overworld(val x: Int, val y: Int) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase
    object PartyDefeated : FfPhase
}
```

- [ ] **Step 2: Write a failing test**

Create `knes-agent/src/test/kotlin/knes/agent/perception/RamObserverTest.kt`:

```kotlin
package knes.agent.perception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RamObserverTest {
    private fun phase(ram: Map<String, Int>): FfPhase = RamObserver.classify(ram)

    @Test
    fun `battle phase`() {
        val ram = mapOf(
            "screenState" to 0x68,
            "enemyMainType" to 0x7C,
            "enemy1_hpLow" to 0x6A, "enemy1_hpHigh" to 0x00,
            "enemy1_dead" to 0,
            "char1_status" to 0, "char2_status" to 0, "char3_status" to 0, "char4_status" to 0,
        )
        assertEquals(FfPhase.Battle(enemyId = 0x7C, enemyHp = 0x6A, enemyDead = false), phase(ram))
    }

    @Test
    fun `party defeated when all chars dead`() {
        val ram = mapOf(
            "screenState" to 0x68,
            "enemyMainType" to 0x7C,
            "enemy1_hpLow" to 0, "enemy1_hpHigh" to 0,
            "enemy1_dead" to 1,
            "char1_status" to 1, "char2_status" to 1, "char3_status" to 1, "char4_status" to 1,
        )
        assertEquals(FfPhase.PartyDefeated, phase(ram))
    }

    @Test
    fun `post battle screen state`() {
        val ram = mapOf("screenState" to 0x63, "char1_status" to 0)
        assertEquals(FfPhase.PostBattle, phase(ram))
    }

    @Test
    fun `overworld coords`() {
        val ram = mapOf(
            "screenState" to 0x00,
            "worldX" to 0x21, "worldY" to 0x14,
            "char1_status" to 0,
        )
        assertEquals(FfPhase.Overworld(x = 0x21, y = 0x14), phase(ram))
    }
}
```

- [ ] **Step 3: Run — confirm it fails**

Run: `./gradlew :knes-agent:test --tests RamObserverTest`
Expected: FAIL — `RamObserver` doesn't exist yet.

- [ ] **Step 4: Implement `RamObserver`**

Create `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt`:

```kotlin
package knes.agent.perception

import knes.agent.tools.EmulatorToolset

class RamObserver(private val toolset: EmulatorToolset) {
    fun observe(): FfPhase = classify(toolset.getState().ram)

    fun ramSnapshot(): Map<String, Int> = toolset.getState().ram

    companion object {
        const val SCREEN_STATE_BATTLE = 0x68
        const val SCREEN_STATE_POST_BATTLE = 0x63

        fun classify(ram: Map<String, Int>): FfPhase {
            val partyDead = (1..4).all { (ram["char${it}_status"] ?: 0) and 0x01 == 0x01 }
            if (partyDead && (1..4).any { ram.containsKey("char${it}_status") }) return FfPhase.PartyDefeated

            return when (ram["screenState"]) {
                SCREEN_STATE_BATTLE -> FfPhase.Battle(
                    enemyId = ram["enemyMainType"] ?: -1,
                    enemyHp = ((ram["enemy1_hpHigh"] ?: 0) shl 8) or (ram["enemy1_hpLow"] ?: 0),
                    enemyDead = (ram["enemy1_dead"] ?: 0) != 0,
                )
                SCREEN_STATE_POST_BATTLE -> FfPhase.PostBattle
                else -> {
                    val x = ram["worldX"]; val y = ram["worldY"]
                    if (x != null && y != null) FfPhase.Overworld(x, y) else FfPhase.TitleOrMenu
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run — confirm green**

Run: `./gradlew :knes-agent:test --tests RamObserverTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception knes-agent/src/test/kotlin/knes/agent/perception
git commit -m "feat(agent): RamObserver classifies FF1 phase from RAM"
```

---

### Task 3.2: `ScreenshotPolicy`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/perception/ScreenshotPolicy.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/ScreenshotPolicyTest.kt`

- [ ] **Step 1: Failing test**

Create `knes-agent/src/test/kotlin/knes/agent/perception/ScreenshotPolicyTest.kt`:

```kotlin
package knes.agent.perception

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenshotPolicyTest {
    @Test
    fun `attaches on first turn`() {
        val p = ScreenshotPolicy()
        assertTrue(p.shouldAttach(previous = null, current = FfPhase.TitleOrMenu))
    }

    @Test
    fun `attaches on phase change`() {
        val p = ScreenshotPolicy()
        assertTrue(p.shouldAttach(previous = FfPhase.Overworld(1, 1), current = FfPhase.Battle(0x7C, 100, false)))
    }

    @Test
    fun `skips when phase identity unchanged`() {
        val p = ScreenshotPolicy()
        // Identity = subclass of FfPhase, NOT field equality (HP changes within Battle don't trigger).
        assertFalse(p.shouldAttach(previous = FfPhase.Battle(0x7C, 100, false), current = FfPhase.Battle(0x7C, 80, false)))
    }
}
```

- [ ] **Step 2: Run — fail**

Run: `./gradlew :knes-agent:test --tests ScreenshotPolicyTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

Create `knes-agent/src/main/kotlin/knes/agent/perception/ScreenshotPolicy.kt`:

```kotlin
package knes.agent.perception

class ScreenshotPolicy {
    fun shouldAttach(previous: FfPhase?, current: FfPhase): Boolean {
        if (previous == null) return true
        return previous::class != current::class
    }
}
```

- [ ] **Step 4: Run — pass**

Run: `./gradlew :knes-agent:test --tests ScreenshotPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/ScreenshotPolicy.kt knes-agent/src/test/kotlin/knes/agent/perception/ScreenshotPolicyTest.kt
git commit -m "feat(agent): ScreenshotPolicy attaches on phase change"
```

---

## Phase 4 — Advisor and Executor agents

### Task 4.1: `AdvisorAgent`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.advisor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import knes.agent.tools.EmulatorToolset

/**
 * Single-shot planner. Given the current observation (text + optional screenshot path),
 * returns a short plan-of-attack the executor will follow.
 */
class AdvisorAgent(
    apiKey: String,
    toolset: EmulatorToolset,
    private val model: AnthropicModels = AnthropicModels.Opus_4_6,
) {
    private val executor = SingleLLMPromptExecutor(AnthropicLLMClient(apiKey))

    private val agent = AIAgent(
        promptExecutor = executor,
        llmModel = model,
        toolRegistry = ToolRegistry { tool(toolset::getState); tool(toolset::getScreen) },
        systemPrompt = """
            You are the planner for an autonomous Final Fantasy (NES) agent.
            Given the current emulator state, output a short numbered plan (1–6 steps) the
            executor will follow until the next phase change. Keep each step actionable
            in terms of the kNES tool surface (step / tap / sequence / execute_action).
            Do NOT execute the plan yourself; only describe it.
        """.trimIndent(),
    )

    suspend fun plan(observation: String): String = agent.run(observation)

    fun asAgent(): AIAgent = agent
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If `SingleLLMPromptExecutor` is named differently in resolved Koog (e.g. `simpleAnthropicExecutor`), use whichever the smoke test from Task 2.4 used.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt
git commit -m "feat(agent): AdvisorAgent (Opus, single-shot planner)"
```

---

### Task 4.2: `ExecutorAgent`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.executor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentStrategies
import ai.koog.agents.core.agent.createAgentTool
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import knes.agent.advisor.AdvisorAgent
import knes.agent.tools.EmulatorToolset

class ExecutorAgent(
    apiKey: String,
    toolset: EmulatorToolset,
    advisor: AdvisorAgent,
    private val model: AnthropicModels = AnthropicModels.Sonnet_4_5,
    private val maxIterationsPerInvocation: Int = 30,
) {
    private val executor = SingleLLMPromptExecutor(AnthropicLLMClient(apiKey))

    private val registry = ToolRegistry {
        tools(toolset)
        tool(advisor.asAgent().createAgentTool(
            agentName = "askAdvisor",
            agentDescription = "Consult the planner when stuck or at a phase boundary. Provide a short reason.",
            inputDescriptor = ToolParameterDescriptor(
                name = "reason",
                description = "Why are you escalating? e.g. 'no RAM progress 20 turns', 'unknown menu screen', 'battle started'",
                type = ToolParameterType.String,
            )
        ))
    }

    private val agent = AIAgent(
        promptExecutor = executor,
        llmModel = model,
        toolRegistry = registry,
        graphStrategy = AIAgentStrategies.reActStrategy(maxIterations = maxIterationsPerInvocation, name = "ff1_executor"),
        systemPrompt = ff1ExecutorSystemPrompt,
    )

    suspend fun run(input: String): String = agent.run(input)

    companion object {
        // Inlined here so the agent is self-contained. Source of truth: docs/ff1-system-prompt.md.
        // If you change this, change the doc too.
        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor. Use the kNES tools to advance
            the game toward defeating Garland (the bridge boss).

            Tool surface: load_rom / step / tap / sequence / get_state / get_screen /
            apply_profile / list_actions / execute_action / press / release / reset.

            Conventions: 60 frames = 1 second. Use tap/sequence over many steps. Set
            screenshot=true only when the visual context changed (you'll usually be told to).

            When uncertain or stuck (no progress, unknown screen, battle starts/ends), call
            askAdvisor("...short reason..."). Otherwise, keep executing the current plan until
            the next phase boundary. Reply DONE when no further action is required this turn.
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If the resolved Koog version names differ (`graphStrategy` vs `strategy`, `tools(toolset)` vs `tools(toolset.asTools())`), follow the patterns confirmed in Task 2.4.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt
git commit -m "feat(agent): ExecutorAgent (Sonnet, reActStrategy, advisor as tool)"
```

---

## Phase 5 — Runtime, success detection, CLI

### Task 5.1: Trace logging

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/Trace.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
data class TraceEvent(
    val turn: Int,
    val role: String,           // "executor" | "advisor" | "watchdog" | "outcome"
    val phase: String,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val toolCalls: List<String> = emptyList(),
    val ramDiff: Map<String, Int> = emptyMap(),
    val screenshot: String? = null,
    val note: String? = null,
)

class Trace(dir: Path) {
    private val json = Json { prettyPrint = false }
    private val out = run {
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("trace.jsonl"))
    }
    private var turn = 0

    fun record(event: TraceEvent) {
        out.appendLine(json.encodeToString(TraceEvent.serializer(), event.copy(turn = ++turn)))
        out.flush()
    }

    fun close() = out.close()

    companion object {
        fun newRunDir(root: Path = Path.of("runs")): Path =
            root.resolve(Instant.now().toString().replace(':', '-'))
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/Trace.kt
git commit -m "feat(agent): JSONL trace logger"
```

---

### Task 5.2: `Outcome` and `SuccessCriteria`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/Outcome.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/SuccessCriteriaTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package knes.agent.runtime

import knes.agent.perception.FfPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuccessCriteriaTest {
    @Test
    fun `victory when garland HP 0`() {
        val outcome = SuccessCriteria.evaluate(FfPhase.Battle(enemyId = GARLAND_ID, enemyHp = 0, enemyDead = true))
        assertEquals(Outcome.Victory, outcome)
    }

    @Test
    fun `not victory when wrong enemy`() {
        val outcome = SuccessCriteria.evaluate(FfPhase.Battle(enemyId = 0x01, enemyHp = 0, enemyDead = true))
        assertEquals(Outcome.InProgress, outcome)
    }

    @Test
    fun `defeat on party wipe`() {
        assertEquals(Outcome.PartyDefeated, SuccessCriteria.evaluate(FfPhase.PartyDefeated))
    }
}
```

- [ ] **Step 2: Implement**

Create `knes-agent/src/main/kotlin/knes/agent/runtime/Outcome.kt`:

```kotlin
package knes.agent.runtime

import knes.agent.perception.FfPhase

/**
 * Garland enemy id in FF1's enemy table. 0x7C is the canonical value used in
 * randomizer/community RAM maps; verify on the first acceptance run by logging
 * `enemyMainType` when the bridge battle starts and updating this constant if it differs.
 */
const val GARLAND_ID = 0x7C

enum class Outcome { InProgress, Victory, PartyDefeated, OutOfBudget, Error }

object SuccessCriteria {
    fun evaluate(phase: FfPhase): Outcome = when (phase) {
        is FfPhase.Battle -> if (phase.enemyId == GARLAND_ID && phase.enemyDead) Outcome.Victory else Outcome.InProgress
        FfPhase.PartyDefeated -> Outcome.PartyDefeated
        else -> Outcome.InProgress
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :knes-agent:test --tests SuccessCriteriaTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/Outcome.kt knes-agent/src/test/kotlin/knes/agent/runtime/SuccessCriteriaTest.kt
git commit -m "feat(agent): Outcome + SuccessCriteria (Garland defeat / party wipe)"
```

---

### Task 5.3: `AgentSession` (outer loop, watchdog, escalation)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.FfPhase
import knes.agent.perception.RamObserver
import knes.agent.perception.ScreenshotPolicy
import knes.agent.tools.EmulatorToolset
import java.nio.file.Path

data class Budget(val maxToolCalls: Int = 2000, val maxAdvisorCalls: Int = 30)

class AgentSession(
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val executor: ExecutorAgent,
    private val advisor: AdvisorAgent,
    private val budget: Budget = Budget(),
    runDir: Path = Trace.newRunDir(),
) {
    private val trace = Trace(runDir)
    private val screenshotPolicy = ScreenshotPolicy()

    /**
     * Drives the agent until success/failure. Each "outer turn":
     *   1. Observe RAM, classify phase.
     *   2. Check SuccessCriteria — terminate if Victory / PartyDefeated.
     *   3. If phase changed since last turn, ask advisor for a plan.
     *   4. Run the executor for up to one phase (its internal reActStrategy iterates;
     *      we re-enter when phase changes or executor returns).
     *   5. Watchdog: bump idleTurns if RAM didn't change; on threshold, force advisor.
     *
     * Termination conditions: SuccessCriteria != InProgress, or budget exhausted.
     */
    suspend fun run(): Outcome {
        var previousPhase: FfPhase? = null
        var currentPlan = "Start the game from the title screen and begin a new game."
        var idleTurns = 0
        var lastRam: Map<String, Int> = emptyMap()
        var advisorCalls = 0
        var toolCalls = 0   // approximate; tracked per executor.run by inspecting trace

        while (true) {
            val phase = observer.observe()
            val ram = observer.ramSnapshot()

            when (val outcome = SuccessCriteria.evaluate(phase)) {
                Outcome.InProgress -> Unit
                else -> { trace.record(TraceEvent(0, "outcome", phase.toString(), note = outcome.name)); trace.close(); return outcome }
            }

            val phaseChanged = previousPhase == null || previousPhase!!::class != phase::class
            if (phaseChanged || idleTurns >= 20) {
                if (++advisorCalls > budget.maxAdvisorCalls) { trace.close(); return Outcome.OutOfBudget }
                val attachShot = screenshotPolicy.shouldAttach(previousPhase, phase)
                val obs = buildString {
                    append("Phase: $phase\nRAM: $ram\n")
                    if (attachShot) append("Screenshot: ${toolset.getScreen().base64.take(64)}…\n")
                    append("Reason: ${if (phaseChanged) "phase change" else "watchdog stuck"}")
                }
                currentPlan = advisor.plan(obs)
                trace.record(TraceEvent(0, "advisor", phase.toString(), note = currentPlan))
                idleTurns = 0
            }

            val executorInput = "Plan:\n$currentPlan\n\nCurrent phase: $phase\nRAM: $ram"
            val result = executor.run(executorInput)
            toolCalls += 1   // per outer turn, conservatively
            trace.record(TraceEvent(0, "executor", phase.toString(), note = result.take(200)))

            val newRam = observer.ramSnapshot()
            idleTurns = if (newRam == lastRam) idleTurns + 1 else 0
            lastRam = newRam
            previousPhase = phase

            if (toolCalls > budget.maxToolCalls) { trace.close(); return Outcome.OutOfBudget }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt
git commit -m "feat(agent): AgentSession outer loop with watchdog and budget"
```

---

### Task 5.4: CLI entry point

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/Main.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.RamObserver
import knes.agent.runtime.AgentSession
import knes.agent.runtime.Budget
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    val rom = args.firstOrNull { it.startsWith("--rom=") }?.removePrefix("--rom=") ?: "roms/ff1.nes"
    val profile = args.firstOrNull { it.startsWith("--profile=") }?.removePrefix("--profile=") ?: "ff1"
    val maxSteps = args.firstOrNull { it.startsWith("--max-steps=") }?.removePrefix("--max-steps=")?.toIntOrNull() ?: 2000
    val key = System.getenv("ANTHROPIC_API_KEY")
        ?: error("ANTHROPIC_API_KEY not set")

    val session = EmulatorSession()
    val toolset = EmulatorToolset(session)
    require(toolset.loadRom(rom).ok) { "Failed to load ROM: $rom" }
    require(toolset.applyProfile(profile).ok) { "Failed to apply profile: $profile" }

    val observer = RamObserver(toolset)
    val advisor = AdvisorAgent(key, toolset)
    val executor = ExecutorAgent(key, toolset, advisor)

    val outcome = AgentSession(
        toolset = toolset,
        observer = observer,
        executor = executor,
        advisor = advisor,
        budget = Budget(maxToolCalls = maxSteps),
    ).run()

    println("OUTCOME: $outcome")
    exitProcess(if (outcome == knes.agent.runtime.Outcome.Victory) 0 else 1)
}
```

- [ ] **Step 2: Compile and link**

Run: `./gradlew :knes-agent:installDist`
Expected: `BUILD SUCCESSFUL`. The launcher script appears in `knes-agent/build/install/knes-agent/bin/`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/Main.kt
git commit -m "feat(agent): CLI entry point"
```

---

## Phase 6 — Acceptance

### Task 6.1: First end-to-end run

- [ ] **Step 1: Confirm Garland enemy id**

Run the agent against a known-good FF1 ROM and watch the trace log. When the bridge battle starts, the trace `phase` line will read `Battle(enemyId=…, …)`. If that id is **not** `0x7C`, update `GARLAND_ID` in `Outcome.kt` and re-run from boot. Commit the fix:

```bash
git commit -am "fix(agent): correct GARLAND_ID after RAM verification"
```

- [ ] **Step 2: Full acceptance run**

```bash
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:run --args="--rom=roms/ff1.nes --profile=ff1 --max-steps=2000"
```

Expected: emulator window opens, agent plays, terminal prints `OUTCOME: Victory`. Save `runs/<timestamp>/trace.jsonl` as evidence.

- [ ] **Step 3: Sanity-check existing MCP integration still works**

Run: `./gradlew :knes-mcp:run` (in-process default), then drive a few tools from Claude Code or `mcp-cli` against the FF1 system prompt. Confirm no regression vs `master`.

- [ ] **Step 4: Final commit**

```bash
git add docs/superpowers/plans/2026-05-01-ff1-koog-agent.md
git commit -m "feat(agent): FF1 Koog agent — Garland defeated end-to-end"
```

---

## Self-review notes

**Spec coverage**: every section of `2026-04-30-ff1-koog-agent-design.md` maps to at least one task here:
- §3 Architecture → Tasks 1.1, 2.1
- §4 Toolset → Tasks 1.2, 1.3, 2.3
- §5 Koog topology → Tasks 4.1, 4.2
- §6 Perception → Tasks 3.1, 3.2
- §7 Escalation → Task 5.3
- §8 Success/failure → Task 5.2
- §9 CLI/runtime → Tasks 5.3, 5.4
- §10 Observability → Task 5.1
- §13 Acceptance → Task 6.1

**Open knowns**: Koog version constants (`AnthropicModels.Sonnet_4_5` / `Opus_4_6`) and a couple of API names (`graphStrategy` vs `strategy`, `SingleLLMPromptExecutor` vs `simpleAnthropicExecutor`) are confirmed against the resolved Koog 0.5.1 in Task 2.4. If 0.5.1 surfaces breaking renames, fix at the Task 2.4 gate before continuing.

**Garland id**: `GARLAND_ID = 0x7C` is a community value, not source-verified in this repo. Task 6.1 step 1 explicitly confirms or corrects it.
