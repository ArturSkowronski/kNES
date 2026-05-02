# FF1 Koog Agent V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace V1's `reActStrategy` inner loop with Koog's `singleRunStrategy`, ship a scripted skill library (PressStartUntilOverworld / CreateDefaultParty / WalkOverworldTo + wrappers for existing FF1 GameActions), wire Anthropic prompt caching, and route models per phase. Acceptance: agent autonomously drives boot → start of Garland battle in ≤ 15 minutes wall-clock and ≤ $3 cost.

**Architecture:** Two new packages in `knes-agent` (`skills/` for the Voyager-style scripted skill library, `llm/` for `AnthropicSession` + `ModelRouter` + `PromptCacheConfig`). The advisor/executor split survives but each agent now does one tool call per LLM invocation; outer loop ownership moves entirely into `AgentSession`. Koog sees only 7 macro tools; raw `step/tap/sequence` remain on `EmulatorToolset` for skills' internal use and for the Ktor/MCP layers.

**Tech Stack:** Kotlin 2.3, JDK 17, Gradle, Koog 0.5.1 (`agents-core`, `agents-tools`, `agents-ext`, `prompt-executor-anthropic-client`, `prompt-executor-llms-all`), Ktor CIO 3.3, Anthropic Sonnet 4.5 / Haiku 4.5 / Opus 4, Kotest 6.1.4.

**Spec:** [`docs/superpowers/specs/2026-05-01-ff1-koog-agent-v2-design.md`](../specs/2026-05-01-ff1-koog-agent-v2-design.md) (601 LOC, post-research).

**Research:** [`docs/superpowers/research/2026-05-01-llm-game-agents.md`](../research/2026-05-01-llm-game-agents.md).

---

## Phase 0 — Worktree

End-of-phase property: a clean isolated git worktree on a fresh branch off master, with `roms/ff.nes` linked.

### Task 0.1: Create worktree on `ff1-agent-v2`

**Files:** none (workspace setup).

- [ ] **Step 1: Create worktree**

```bash
cd /Users/askowronski/Priv/kNES
git fetch origin master
git worktree add ../kNES-ff1-agent-v2 -b ff1-agent-v2 origin/master
```

Expected: `Preparing worktree (new branch 'ff1-agent-v2')` followed by `HEAD is now at 2b2d777 spec: FF1 agent V2 design …`.

- [ ] **Step 2: Symlink the ROM directory**

```bash
ln -sf /Users/askowronski/Priv/kNES/roms ../kNES-ff1-agent-v2/roms
ls ../kNES-ff1-agent-v2/roms/
```

Expected: `ff.nes`, `knes.nes` listed. `roms` is in `.gitignore` (added in V1) so the symlink itself is never committed.

- [ ] **Step 3: Verify clean baseline build**

```bash
cd ../kNES-ff1-agent-v2 && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify V1 live tests still skip cleanly without an API key**

```bash
unset ANTHROPIC_API_KEY
./gradlew :knes-agent:test
```

Expected: `BUILD SUCCESSFUL`; `AnthropicSmokeTest` and `ReactSmokeTest` self-skip (Kotest treats early `return@test` as pass).

No commit yet — Phase 0 ships nothing on the new branch.

---

## Phase 1 — `AnthropicSession` and `ModelRouter` (LLM plumbing, no behavior change yet)

End-of-phase property: a long-lived Anthropic client wrapper exists, V1 agents still pass their tests because they accept either an `apiKey` or an `AnthropicSession`. No outer behavior change.

### Task 1.1: Probe Koog 0.5.1 cache-control surface

**Files:** none (research-only step that produces a finding to commit).

- [ ] **Step 1: Inspect the Anthropic client jar**

```bash
JAR=$(find ~/.gradle/caches -path '*ai/koog/prompt-executor-anthropic-client-jvm*0.5.1*' -name '*.jar' | head -1)
echo $JAR
unzip -l "$JAR" | grep -iE 'Cache|Settings' | head
```

Read the candidate class names. Then dump the `AnthropicLLMClientSettings` class signature:

```bash
javap -p -classpath "$JAR" ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClientSettings 2>/dev/null | head -40
```

- [ ] **Step 2: Inspect the prompt DSL for cache markers**

```bash
JAR_DSL=$(find ~/.gradle/caches -path '*ai/koog/prompt-dsl*0.5.1*' -name '*.jar' | head -1)
unzip -l "$JAR_DSL" | grep -iE 'cache|control' | head
```

- [ ] **Step 3: Record findings**

Create `docs/superpowers/notes/2026-05-02-koog-cache-probe.md` with:

- The exact class path and members of `AnthropicLLMClientSettings`.
- Whether the prompt DSL has any `cacheControl` builder or attribute.
- A one-line decision: "use Koog's wrapper" OR "fall back to direct `Anthropic-Beta: prompt-caching-2024-07-31` headers via a custom HttpClient".

Skip the file if both inspections return nothing relevant — instead record `"none found"` and the decision becomes "fall back" by default.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/notes/2026-05-02-koog-cache-probe.md
git commit -m "research: Koog 0.5.1 prompt-cache surface probe"
```

### Task 1.2: `AnthropicSession` skeleton

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/llm/AnthropicSession.kt`

- [ ] **Step 1: Write the file**

```kotlin
package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor

/**
 * Long-lived Anthropic client + Koog single-LLM executor for one agent run.
 *
 * V1 built a fresh AnthropicLLMClient per turn (defeating prompt caching). V2 keeps one
 * instance for the lifetime of an AgentSession so static prefixes (system prompt, tool
 * descriptions) hit the cache across turns. See spec §6.
 *
 * Cache markers are configured per-prompt in PromptCacheConfig (Task 1.4) — this class
 * just owns the connection.
 */
class AnthropicSession(apiKey: String) : AutoCloseable {
    val client: AnthropicLLMClient = AnthropicLLMClient(apiKey = apiKey)
    val executor: SingleLLMPromptExecutor = SingleLLMPromptExecutor(client)

    override fun close() {
        // Koog uses Ktor's CIO under the hood. Closing the client releases its coroutine
        // resources. Required because long-lived sessions must clean up on JVM exit.
        // If AnthropicLLMClient does not implement Closeable in 0.5.1, this is a no-op
        // and the GC will reclaim resources.
        (client as? AutoCloseable)?.close()
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/llm/AnthropicSession.kt
git commit -m "feat(agent): AnthropicSession (long-lived client wrapper)"
```

### Task 1.3: `ModelRouter`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/llm/ModelRouter.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/llm/ModelRouterTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.FfPhase

class ModelRouterTest : FunSpec({
    val router = ModelRouter()

    test("executor in TitleOrMenu uses Sonnet 4.5") {
        router.modelFor(FfPhase.TitleOrMenu, AgentRole.EXECUTOR) shouldBe AnthropicModels.Sonnet_4_5
    }
    test("advisor in TitleOrMenu uses Opus 4") {
        router.modelFor(FfPhase.TitleOrMenu, AgentRole.ADVISOR) shouldBe AnthropicModels.Opus_4
    }
    test("executor in Overworld uses Haiku 4.5") {
        router.modelFor(FfPhase.Overworld(0, 0), AgentRole.EXECUTOR) shouldBe AnthropicModels.Haiku_4_5
    }
    test("advisor in Overworld uses Sonnet 4.5") {
        router.modelFor(FfPhase.Overworld(0, 0), AgentRole.ADVISOR) shouldBe AnthropicModels.Sonnet_4_5
    }
    test("executor in Battle uses Haiku 4.5") {
        router.modelFor(FfPhase.Battle(0x7C, 100, false), AgentRole.EXECUTOR) shouldBe AnthropicModels.Haiku_4_5
    }
})
```

- [ ] **Step 2: Verify it fails**

Run: `./gradlew :knes-agent:test --tests "*ModelRouterTest*"`
Expected: `FAIL` — class not found.

- [ ] **Step 3: Implement**

```kotlin
package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import knes.agent.perception.FfPhase

enum class AgentRole { EXECUTOR, ADVISOR }

/**
 * Route per (phase, role) → model. See spec §7 for rationale and pricing.
 *
 * Haiku 4.5 is 15× cheaper than Sonnet, 75× cheaper than Opus. We use it wherever the
 * choice is "pick which scripted skill to invoke" — Overworld, Battle, PostBattle. Sonnet
 * runs uncertain pre-game phases. Opus only advises on novel/uncertain pre-game phases.
 */
class ModelRouter {
    fun modelFor(phase: FfPhase, role: AgentRole): LLModel = when (phase) {
        FfPhase.Boot, FfPhase.TitleOrMenu, FfPhase.NewGameMenu, FfPhase.NameEntry ->
            if (role == AgentRole.EXECUTOR) AnthropicModels.Sonnet_4_5 else AnthropicModels.Opus_4
        is FfPhase.Overworld, is FfPhase.Battle, FfPhase.PostBattle, FfPhase.PartyDefeated ->
            if (role == AgentRole.EXECUTOR) AnthropicModels.Haiku_4_5 else AnthropicModels.Sonnet_4_5
    }
}
```

- [ ] **Step 4: NewGameMenu and NameEntry don't yet exist on `FfPhase`**

Compile will fail. Add the two new objects to `knes-agent/src/main/kotlin/knes/agent/perception/FfPhase.kt`:

```kotlin
sealed interface FfPhase {
    object Boot : FfPhase { override fun toString() = "Boot" }
    object TitleOrMenu : FfPhase { override fun toString() = "TitleOrMenu" }
    object NewGameMenu : FfPhase { override fun toString() = "NewGameMenu" }
    object NameEntry : FfPhase { override fun toString() = "NameEntry" }
    data class Overworld(val x: Int, val y: Int) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase { override fun toString() = "PostBattle" }
    object PartyDefeated : FfPhase { override fun toString() = "PartyDefeated" }
}
```

`RamObserver.classify` does **not** yet emit these — Phase 5 (Task 5.1) wires the actual detection. They exist now only as type symbols so `ModelRouter` is exhaustive over `FfPhase`.

- [ ] **Step 5: Run tests, expect green**

Run: `./gradlew :knes-agent:test --tests "*ModelRouterTest*"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/llm/ModelRouter.kt \
        knes-agent/src/test/kotlin/knes/agent/llm/ModelRouterTest.kt \
        knes-agent/src/main/kotlin/knes/agent/perception/FfPhase.kt
git commit -m "feat(agent): ModelRouter (per-phase, per-role model selection) + extend FfPhase"
```

### Task 1.4: `PromptCacheConfig` shim

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/llm/PromptCacheConfig.kt`

This file's body depends on Task 1.1's findings. Two paths:

**Path A** (Koog exposes `cacheControl` per-message): write thin wrappers that mark `system` and the static-preamble user message as cached.

**Path B** (Koog does not expose it): file is a stub with `apply(prompt: Prompt): Prompt = prompt` and a TODO comment pointing at V2.1 (where we'd swap in a custom HttpClient). V2 still benefits from long-lived client / fewer cold connections; full caching becomes a follow-up.

- [ ] **Step 1: Read the cache-probe note**

```bash
cat docs/superpowers/notes/2026-05-02-koog-cache-probe.md
```

The decision line in that file selects path A or B for this task.

- [ ] **Step 2A: If path A (Koog supports cache markers)**

Implement using whatever DSL Task 1.1 documented. Roughly:

```kotlin
package knes.agent.llm

import ai.koog.prompt.dsl.Prompt
// + whatever cache-marker import the probe note documented

/**
 * Marks the system prompt + tool descriptions and the static run preamble as cacheable
 * (Anthropic prompt-cache, see spec §6). Caller assembles the prompt; this just wires
 * the cache_control breakpoints.
 */
object PromptCacheConfig {
    /** Mark the system message of [prompt] as cacheable (breakpoint #1). */
    fun cacheSystem(prompt: Prompt): Prompt {
        // Koog DSL call from Task 1.1 findings.
        return prompt
    }

    /** Mark the static run preamble (everything up to the rolling state) as cacheable (#2). */
    fun cachePreamble(prompt: Prompt, preambleEndIndex: Int): Prompt {
        return prompt
    }
}
```

- [ ] **Step 2B: If path B (no Koog support)**

```kotlin
package knes.agent.llm

import ai.koog.prompt.dsl.Prompt

/**
 * Path B per Task 1.1 probe: Koog 0.5.1 does not expose Anthropic cache_control breakpoints.
 * We still get partial caching benefit from a long-lived AnthropicLLMClient (fewer cold
 * connections, plus internal client-side prompt comparison). Full cache_control wiring is
 * deferred to V2.1, where we either swap in a custom HttpClient or upgrade Koog.
 *
 * This object is intentionally a no-op so callers can write the same code under both paths
 * without conditionals scattered around.
 */
object PromptCacheConfig {
    fun cacheSystem(prompt: Prompt): Prompt = prompt
    fun cachePreamble(prompt: Prompt, preambleEndIndex: Int): Prompt = prompt
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/llm/PromptCacheConfig.kt
git commit -m "feat(agent): PromptCacheConfig (path A or B per probe)"
```

---

## Phase 2 — `Skill` interface and first scripted skill end-to-end

End-of-phase property: `Skill` interface + `SkillResult` exist; `PressStartUntilOverworld` skill is testable in standalone mode against a real ROM and successfully advances `bootFlag` to `0x4D`.

### Task 2.1: `Skill` interface and `SkillResult`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/Skill.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/SkillResult.kt`

- [ ] **Step 1: Write `SkillResult`**

```kotlin
package knes.agent.skills

import kotlinx.serialization.Serializable

@Serializable
data class SkillResult(
    val ok: Boolean,
    val message: String,
    val framesElapsed: Int = 0,
    val ramAfter: Map<String, Int> = emptyMap(),
)
```

- [ ] **Step 2: Write `Skill` interface**

```kotlin
package knes.agent.skills

/**
 * One scripted FF1 macro. Implementations call EmulatorToolset directly to drive the game;
 * the LLM only chooses which Skill to invoke (via the @Tool methods on SkillRegistry).
 *
 * See spec §5 for design rationale (Voyager skill library + CPP navigator).
 */
interface Skill {
    val id: String                              // stable identifier, snake_case
    val description: String                     // surfaced as @LLMDescription text
    suspend fun invoke(args: Map<String, String> = emptyMap()): SkillResult
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/
git commit -m "feat(agent): Skill interface + SkillResult"
```

### Task 2.2: `PressStartUntilOverworld` skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/PressStartUntilOverworld.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/skills/PressStartUntilOverworldTest.kt`

- [ ] **Step 1: Write the failing test (live, requires ROM)**

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class PressStartUntilOverworldTest : FunSpec({
    test("advances bootFlag to 0x4D from a fresh boot") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test  // skip when ROM unavailable on CI

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom).ok shouldBe true
        toolset.applyProfile("ff1").ok shouldBe true

        val result = PressStartUntilOverworld(toolset).invoke()

        result.ok.shouldBeTrue()
        result.ramAfter["bootFlag"] shouldBe 0x4D
    }
})
```

- [ ] **Step 2: Verify it fails**

Run: `./gradlew :knes-agent:test --tests "*PressStartUntilOverworld*"`
Expected: FAIL — `PressStartUntilOverworld` does not exist.

- [ ] **Step 3: Implement the skill**

```kotlin
package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Tap START until FF1's bootFlag (RAM 0x00F9) becomes 0x4D, indicating in-game state
 * after the title screen / NEW GAME confirmation. See profile ff1.json:28.
 *
 * Strategy: tap START, gap 30 frames, observe RAM. Up to maxAttempts. Falls back to A
 * after 10 unproductive START taps (intro cinematic sometimes wants A).
 */
class PressStartUntilOverworld(private val toolset: EmulatorToolset) : Skill {
    override val id = "press_start_until_overworld"
    override val description =
        "Tap START until the game advances past the title screen / NEW GAME menu. " +
            "Bounded by maxAttempts (default 60). Falls back to A after 10 START taps without progress."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxAttempts = args["maxAttempts"]?.toIntOrNull() ?: 60
        var attempts = 0
        var totalFrames = 0
        var unproductiveStarts = 0
        var lastBootFlag = toolset.getState().ram["bootFlag"] ?: 0
        while (attempts < maxAttempts) {
            val button = if (unproductiveStarts >= 10) "A" else "START"
            val tap = toolset.tap(button = button, count = 1, pressFrames = 5, gapFrames = 30)
            totalFrames += tap.frame
            attempts++
            val ram = toolset.getState().ram
            val bootFlag = ram["bootFlag"] ?: 0
            if (bootFlag == 0x4D) {
                return SkillResult(
                    ok = true,
                    message = "bootFlag flipped after $attempts taps",
                    framesElapsed = totalFrames,
                    ramAfter = ram,
                )
            }
            if (bootFlag == lastBootFlag) unproductiveStarts++ else unproductiveStarts = 0
            lastBootFlag = bootFlag
        }
        val ram = toolset.getState().ram
        return SkillResult(
            ok = false,
            message = "bootFlag never reached 0x4D after $maxAttempts taps",
            framesElapsed = totalFrames,
            ramAfter = ram,
        )
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :knes-agent:test --tests "*PressStartUntilOverworld*"
```

Expected: PASS in ~10-30 seconds (real emulator running). If `bootFlag` never flips, the FF1 title-skip pattern (https://www.speedrun.com/final_fantasy_nes/guides/vk3vf) may need extra A presses earlier — adjust the threshold from 10 to 5.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/PressStartUntilOverworld.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/PressStartUntilOverworldTest.kt
git commit -m "feat(agent): PressStartUntilOverworld skill"
```

---

## Phase 3 — Remaining skills

End-of-phase property: `CreateDefaultParty` and `WalkOverworldTo` exist as `Skill`s with at least smoke-level testing; `SkillRegistry` registers all three new skills + wraps existing GameActions.

### Task 3.1: RAM signature recorder for empirical phases

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/perception/RamSignatureRecorderTest.kt`

We need empirical RAM constants for `NewGameMenu`, `NameEntry`, and the stable post-name `Overworld(start)` state. This task drives the emulator with a hard-coded sequence of inputs that reach each phase, dumps RAM, and writes a signature file used by Task 5.1.

- [ ] **Step 1: Write the recorder test**

```kotlin
package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File
import java.nio.file.Files

class RamSignatureRecorderTest : FunSpec({
    test("record RAM signatures for V2 phases") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")

        val out = StringBuilder()
        fun snapshot(label: String) {
            val ram = toolset.getState().ram
            out.appendLine("== $label ==")
            ram.toSortedMap().forEach { (k, v) -> out.appendLine("  $k = 0x${v.toString(16).padStart(2, '0')} ($v)") }
            out.appendLine()
        }

        // Phase: TitleOrMenu (just after boot)
        toolset.step(buttons = emptyList(), frames = 240)  // let title settle
        snapshot("TitleOrMenu_initial")

        // Tap START once → reach NewGameMenu (or somewhere close)
        toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
        snapshot("AfterFirstStartTap")

        // Tap START again → reach NameEntry (probably)
        toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
        snapshot("AfterSecondStartTap")

        // Tap A a few times to traverse class-select / name-entry confirms
        toolset.tap(button = "A", count = 4, pressFrames = 5, gapFrames = 30)
        snapshot("After4ATaps")

        // Continue tapping A to push through whatever screens remain (~20 taps)
        toolset.tap(button = "A", count = 20, pressFrames = 5, gapFrames = 30)
        snapshot("After24ATaps")

        // Final state — likely Overworld with bootFlag = 0x4D
        snapshot("FinalState")

        Files.writeString(File("docs/superpowers/notes/2026-05-02-ff1-ram-signatures.md").toPath(),
            "# FF1 RAM signatures (recorded ${java.time.Instant.now()})\n\n" + out)
    }
})
```

- [ ] **Step 2: Run with ROM**

```bash
./gradlew :knes-agent:test --tests "*RamSignatureRecorderTest*"
```

Expected: PASS in ~30-60 seconds. File `docs/superpowers/notes/2026-05-02-ff1-ram-signatures.md` is written.

- [ ] **Step 3: Inspect the file**

```bash
head -200 docs/superpowers/notes/2026-05-02-ff1-ram-signatures.md
```

The reader (Phase 5 implementer) needs to identify:
- `screenState` and `menuCursor` values that cleanly distinguish `NewGameMenu` from `NameEntry`.
- Whether `bootFlag` flips at NEW GAME confirm or after party creation.
- A unique RAM marker for "party fully created" (probably any of `char[1..4]_status` becoming `0x00`).

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/notes/2026-05-02-ff1-ram-signatures.md \
        knes-agent/src/test/kotlin/knes/agent/perception/RamSignatureRecorderTest.kt
git commit -m "research: empirical FF1 RAM signatures for V2 phases"
```

### Task 3.2: `CreateDefaultParty` skill

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/CreateDefaultParty.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/skills/CreateDefaultPartyTest.kt`

- [ ] **Step 1: Failing test (skipped when no ROM)**

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class CreateDefaultPartyTest : FunSpec({
    test("creates four characters and reaches in-game") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")
        PressStartUntilOverworld(toolset).invoke()  // dependency: must be at NEW GAME entry

        val result = CreateDefaultParty(toolset).invoke()
        result.ok.shouldBeTrue()

        // After party creation all four char_status fields should not be 0xFF.
        val ram = toolset.getState().ram
        (1..4).forEach { i ->
            val status = ram["char${i}_status"] ?: error("char${i}_status missing")
            require(status != 0xFF) { "char${i}_status still 0xFF — party not created" }
        }
    }
})
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew :knes-agent:test --tests "*CreateDefaultParty*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package knes.agent.skills

import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.StepEntry

/**
 * Scripted FF1 party creation.
 *
 * Class indices (datacrystal.tcrf.net/wiki/Final_Fantasy_(NES)):
 *   0 FIGHTER   1 THIEF   2 BLACK_BELT   3 RED_MAGE   4 WHITE_MAGE   5 BLACK_MAGE
 *
 * Default party: FIGHTER, FIGHTER, WHITE_MAGE, BLACK_MAGE (balanced for early game).
 * Names are auto-generated (H1..H4) — minimal taps in the name-entry grid.
 *
 * Termination check: all four char[N]_status leave the 0xFF uninitialized state.
 */
class CreateDefaultParty(private val toolset: EmulatorToolset) : Skill {
    override val id = "create_default_party"
    override val description =
        "Scripted FF1 character creation: 2× FIGHTER, WHITE_MAGE, BLACK_MAGE with auto-names. " +
            "Assumes the game is at the post-NEW-GAME class-select screen."

    private val defaultClasses = listOf(0, 0, 4, 5)

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        var totalFrames = 0
        for ((slotIndex, classIdx) in defaultClasses.withIndex()) {
            // Move cursor down classIdx times, press A.
            val sequenceSteps = mutableListOf<StepEntry>()
            repeat(classIdx) {
                sequenceSteps += StepEntry(buttons = listOf("DOWN"), frames = 4)
                sequenceSteps += StepEntry(buttons = emptyList(), frames = 12)
            }
            sequenceSteps += StepEntry(buttons = listOf("A"), frames = 5)
            sequenceSteps += StepEntry(buttons = emptyList(), frames = 20)
            val r = toolset.sequence(sequenceSteps)
            totalFrames += r.frame

            // Name entry: just press END (LEFT-most letter is "A"; we want a 1-char name).
            // Default name is "H{slotIndex+1}" — but to keep this skill simple we just hit
            // SELECT (END button mapping varies; FF1 uses SELECT for END on the name grid).
            val r2 = toolset.tap(button = "SELECT", count = 1, pressFrames = 5, gapFrames = 30)
            totalFrames += r2.frame

            // Confirm character with A.
            val r3 = toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            totalFrames += r3.frame
        }
        // After all 4 characters: confirm whole party (A on YES).
        val r = toolset.tap(button = "A", count = 3, pressFrames = 5, gapFrames = 30)
        totalFrames += r.frame

        // Wait a few seconds for the post-confirmation cinematic / fade-in.
        toolset.step(buttons = emptyList(), frames = 180)

        val ram = toolset.getState().ram
        val partyOk = (1..4).all { (ram["char${it}_status"] ?: 0xFF) != 0xFF }
        return SkillResult(
            ok = partyOk,
            message = if (partyOk) "Party created" else "Party still uninitialized",
            framesElapsed = totalFrames,
            ramAfter = ram,
        )
    }
}
```

- [ ] **Step 4: Run, observe**

```bash
./gradlew :knes-agent:test --tests "*CreateDefaultParty*"
```

Expected: PASS, but realistically may need iteration. If the test fails, **examine the trace from `RamSignatureRecorderTest`** (Task 3.1) to understand which screens the script actually traverses. Adjust the input sequence — typical fixes: more A taps after class confirm to skip a confirm screen, or DOWN counts off by one because the class menu starts at FIGHTER (index 0).

If the FF1 name-entry screen doesn't accept `SELECT` as END, the fallback is to navigate to the END tile in the letter grid with DOWN/RIGHT and press A. This is documented at strategywiki.org/wiki/Final_Fantasy/Walkthrough.

Mark this task DONE_WITH_CONCERNS if partial — Phase 5's actual run may force one more iteration here.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/CreateDefaultParty.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/CreateDefaultPartyTest.kt
git commit -m "feat(agent): CreateDefaultParty skill"
```

### Task 3.3: `WalkOverworldTo` skill (path-based variant)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/WalkOverworldTo.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/skills/WalkOverworldToTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class WalkOverworldToTest : FunSpec({
    test("moves at least one tile in the requested direction") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")
        PressStartUntilOverworld(toolset).invoke()
        CreateDefaultParty(toolset).invoke()

        val before = toolset.getState().ram
        val sx = before["worldX"] ?: 0
        val sy = before["worldY"] ?: 0

        // Walk one tile right.
        val result = WalkOverworldTo(toolset).invoke(
            mapOf("targetX" to "${sx + 1}", "targetY" to "$sy", "maxSteps" to "5")
        )
        result.ok.shouldBeTrue()

        val after = toolset.getState().ram
        val ax = after["worldX"] ?: 0
        require(ax == sx + 1 || (after["screenState"] ?: 0) == 0x68) {
            "Did not advance worldX (was $sx, now $ax) and not in battle"
        }
    }
})
```

- [ ] **Step 2: Verify it fails**

Run: `./gradlew :knes-agent:test --tests "*WalkOverworldTo*"`
Expected: FAIL.

- [ ] **Step 3: Implement (greedy direction-picker, abort-on-encounter)**

```kotlin
package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Greedy walk on FF1 overworld toward (targetX, targetY).
 *
 * Each step holds a direction button for FRAMES_PER_TILE frames (FF1 default 16).
 * If RAM screenState becomes 0x68 (battle), returns ok=true with message "encounter":
 * the agent's outer loop will see the Battle phase next observation.
 *
 * V2 uses greedy direction selection (no obstacle awareness). For boot→Coneria-bridge
 * this is sufficient because the path is roughly L-shaped in open overworld. If we hit
 * water/mountain, V3 should add A* over the walkable tile table.
 */
class WalkOverworldTo(private val toolset: EmulatorToolset) : Skill {
    override val id = "walk_overworld_to"
    override val description =
        "Walk on the FF1 overworld toward (targetX, targetY) greedily, one tile at a time. " +
            "Aborts on random encounter (returns ok=true so the outer loop handles the battle)."

    private val FRAMES_PER_TILE = 16

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args["targetX"]?.toIntOrNull() ?: return SkillResult(false, "missing targetX")
        val ty = args["targetY"]?.toIntOrNull() ?: return SkillResult(false, "missing targetY")
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 200
        var stepsTaken = 0
        var totalFrames = 0
        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            if ((ram["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram)
            }
            val cx = ram["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ram["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "reached ($tx,$ty) in $stepsTaken steps", totalFrames, ram)
            }
            val dir = when {
                cx < tx -> "RIGHT"
                cx > tx -> "LEFT"
                cy < ty -> "DOWN"
                else -> "UP"
            }
            val r = toolset.step(buttons = listOf(dir), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++
        }
        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
```

- [ ] **Step 4: Run**

```bash
./gradlew :knes-agent:test --tests "*WalkOverworldTo*"
```

Expected: PASS. If `worldX` doesn't increment after RIGHT step, the starting tile may face a wall — try moving DOWN first or change `before` logic to walk DOWN by 1 instead.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/WalkOverworldTo.kt \
        knes-agent/src/test/kotlin/knes/agent/skills/WalkOverworldToTest.kt
git commit -m "feat(agent): WalkOverworldTo skill (greedy)"
```

### Task 3.4: `SkillRegistry` (Koog ToolSet)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt`

- [ ] **Step 1: Write the registry**

```kotlin
package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ActionToolResult
import knes.agent.tools.results.StateSnapshot

/**
 * V2's reduced LLM-facing tool surface (spec §5).
 *
 *   pressStartUntilOverworld / createDefaultParty / walkOverworldTo  — new V2 skills
 *   battleFightAll / walkUntilEncounter                              — wrappers around existing
 *                                                                      ff1 GameActions
 *   getState                                                         — read-only state
 *
 * `askAdvisor` is registered separately by the executor (it lives on the advisor side).
 *
 * Raw step/tap/sequence/press/release/loadRom/reset/applyProfile remain on EmulatorToolset
 * (used by Skill implementations and by the Ktor / MCP layers) but are NOT in this ToolSet.
 */
@LLMDescription(
    "FF1 macro skills: scripted high-level actions that drive the emulator. Pick one per " +
        "outer turn; observe the resulting RAM state and choose the next skill."
)
class SkillRegistry(private val toolset: EmulatorToolset) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val createPartySkill = CreateDefaultParty(toolset)
    private val walkSkill = WalkOverworldTo(toolset)

    @Tool
    @LLMDescription(
        "Tap START until the game leaves the title screen / NEW GAME menu (FF1 bootFlag = 0x4D). " +
            "Bounded by maxAttempts (default 60)."
    )
    suspend fun pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult =
        pressStartSkill.invoke(mapOf("maxAttempts" to "$maxAttempts"))

    @Tool
    @LLMDescription(
        "Scripted FF1 character creation: 2× FIGHTER, WHITE_MAGE, BLACK_MAGE with auto-names. " +
            "Assumes the game is at the class-select screen after pressStartUntilOverworld."
    )
    suspend fun createDefaultParty(): SkillResult = createPartySkill.invoke()

    @Tool
    @LLMDescription(
        "Walk on the FF1 overworld toward (targetX, targetY) greedily, one tile at a time. " +
            "Returns ok=true if the target is reached OR a random encounter starts."
    )
    suspend fun walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 200): SkillResult =
        walkSkill.invoke(mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription("Run the registered FF1 battle_fight_all action: every alive character uses FIGHT until the battle ends.")
    suspend fun battleFightAll(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")

    @Tool
    @LLMDescription("Run the registered FF1 walk_until_encounter action: walk randomly until a battle starts.")
    suspend fun walkUntilEncounter(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "walk_until_encounter")

    @Tool
    @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = toolset.getState()
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/skills/SkillRegistry.kt
git commit -m "feat(agent): SkillRegistry (7-tool Koog facade)"
```

---

## Phase 4 — Pipeline rewire

End-of-phase property: `AdvisorAgent` and `ExecutorAgent` use `singleRunStrategy`, accept `AnthropicSession` + `ModelRouter`, register `SkillRegistry` (executor) / `ReadOnlyToolset` (advisor). V1 smoke tests still pass.

### Task 4.1: `AdvisorAgent` rewire

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt`

- [ ] **Step 1: Locate `singleRunStrategy` exact path**

```bash
JAR=$(find ~/.gradle/caches -path '*ai/koog/agents-ext*0.5.1*-jvm.jar' | head -1)
unzip -l "$JAR" | grep -iE 'SingleRun|Strategies' | head
```

Confirm the import path. Spec §4 cites `ai.koog.agents.ext.agent.singleRunStrategy` — verify the actual symbol name (might be `SingleRunStrategiesKt.singleRunStrategy`). Note the result for use in this task and Task 4.2.

- [ ] **Step 2: Rewrite `AdvisorAgent`**

Replace the file body with:

```kotlin
package knes.agent.advisor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.agent.singleRunStrategy
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.tools.EmulatorToolset

/**
 * Single-shot planner. Each plan() call performs ONE LLM invocation (singleRunStrategy);
 * the agent returns either a plain-text plan or a single tool call (the model may invoke
 * getState/getScreen to refresh observation).
 *
 * Read-only access: only ReadOnlyToolset (getState, getScreen). The advisor must never
 * mutate emulator state.
 */
class AdvisorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
) {
    private val readOnlyTools = ReadOnlyToolset(toolset)
    private val registry = ToolRegistry { tools(readOnlyTools) }

    private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
        promptExecutor = anthropic.executor,
        llmModel = modelRouter.modelFor(phase, AgentRole.ADVISOR),
        toolRegistry = registry,
        strategy = singleRunStrategy(name = "ff1_advisor"),
        systemPrompt = systemPrompt,
    )

    suspend fun plan(phase: FfPhase, observation: String): String =
        newAgent(phase).run(observation)

    companion object {
        val systemPrompt: String = """
            You are the planner for an autonomous Final Fantasy (NES) agent.
            Given the current emulator state, output a short numbered plan (1–6 steps) the
            executor will follow until the next phase change. Each step must be actionable
            using the available kNES skills (pressStartUntilOverworld, createDefaultParty,
            walkOverworldTo, battleFightAll, walkUntilEncounter).
            Do NOT execute the plan yourself; only describe it as text.
        """.trimIndent()
    }
}
```

- [ ] **Step 3: Update callers (V1 wiring still references `apiKey: String`)**

Search:

```bash
grep -rn "AdvisorAgent(" knes-agent/src
```

`Main.kt` constructs `AdvisorAgent(key, toolset)` today. We update that in Task 4.4 once both agents and `AgentSession` accept the new dependencies. For now this file does not yet compile from `Main.kt`'s call site — that's expected and Task 4.4 fixes it. Run `./gradlew :knes-agent:compileKotlin` only on the source set this task touched:

```bash
./gradlew :knes-agent:compileKotlin -x compileTestKotlin
```

If Kotlin still bails because `Main.kt` is in the same source set, temporarily wrap `Main.kt`'s `AdvisorAgent(...)` call in a TODO that constructs a placeholder session + router. This is a pragmatic exception to the "every commit compiles" rule because Phase 4 is a coordinated rewire; alternatively land Tasks 4.1–4.4 as a single commit (preferred). Choose whichever is less work; if you single-commit, skip the per-task commits below and only commit at the end of Task 4.4.

- [ ] **Step 4: Commit (or defer to Task 4.4)**

If single-commit strategy:

```bash
# defer
```

Otherwise:

```bash
git add knes-agent/src/main/kotlin/knes/agent/advisor/AdvisorAgent.kt
git commit -m "refactor(agent): AdvisorAgent uses singleRunStrategy + AnthropicSession + ModelRouter"
```

### Task 4.2: `ExecutorAgent` rewire

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/executor/ExecutorAgent.kt`

- [ ] **Step 1: Rewrite the executor**

```kotlin
package knes.agent.executor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.agent.singleRunStrategy
import knes.agent.advisor.AdvisorAgent
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

/**
 * Per-outer-turn LLM executor. Single LLM invocation per call (singleRunStrategy):
 *  - tool call → Koog runs the SkillRegistry tool, returns its result as agent output
 *  - plain text → that text is the output
 * The outer AgentSession loop owns iteration; this class is intentionally one-shot.
 */
class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val advisor: AdvisorAgent,
) {
    private val skillRegistry = SkillRegistry(toolset)
    private val advisorTool = AdvisorToolset(advisor)
    private val registry = ToolRegistry {
        tools(skillRegistry)
        tools(advisorTool)
    }

    private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
        promptExecutor = anthropic.executor,
        llmModel = modelRouter.modelFor(phase, AgentRole.EXECUTOR),
        toolRegistry = registry,
        strategy = singleRunStrategy(name = "ff1_executor"),
        systemPrompt = ff1ExecutorSystemPrompt,
    )

    suspend fun run(phase: FfPhase, input: String): String = newAgent(phase).run(input)

    companion object {
        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor. Drive the game toward
            the start of the Garland battle by invoking exactly one scripted skill per
            turn (or asking the advisor when stuck).

            Skills available this turn (each is a single tool call):
            - pressStartUntilOverworld(maxAttempts)
            - createDefaultParty()
            - walkOverworldTo(targetX, targetY, maxSteps)
            - battleFightAll()
            - walkUntilEncounter()
            - getState()
            - askAdvisor(reason)

            Conventions:
            - Pick exactly one tool. Do not narrate state — just choose a skill.
            - The outer loop will observe RAM after your skill returns and call you again.
            - When uncertain (unfamiliar phase, last skill failed, stuck), call askAdvisor.
            - Do NOT call getState repeatedly to "look around"; call a skill that advances state.
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Update `AdvisorToolset` (existing file from V1) for compile parity**

The existing `AdvisorToolset` is at `knes-agent/src/main/kotlin/knes/agent/executor/AdvisorToolset.kt`. It calls `advisor.plan(reason)`. The new `AdvisorAgent.plan` signature is `plan(phase: FfPhase, observation: String)`.

Choose a default phase for the askAdvisor tool (the LLM doesn't know its current phase from inside the askAdvisor call — pass it through):

```kotlin
package knes.agent.executor

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.advisor.AdvisorAgent
import knes.agent.perception.FfPhase

@LLMDescription("Advisor consultation tool.")
class AdvisorToolset(private val advisor: AdvisorAgent) : ToolSet {
    @Tool
    @LLMDescription("Consult the planner when stuck or at a phase boundary. Provide a short reason. Returns a numbered plan.")
    suspend fun askAdvisor(reason: String): String =
        advisor.plan(FfPhase.TitleOrMenu, reason)  // phase is set by the runtime in normal advisor calls; this tool path is rare
}
```

For V2 the executor-invoked `askAdvisor` always passes `FfPhase.TitleOrMenu` because it's the broadest assumption (Opus model). The "real" advisor calls in `AgentSession.run` use the actual current phase. This is a minor cost concession — `askAdvisor` from the LLM is uncommon enough that tuning the model choice here is YAGNI for V2.

- [ ] **Step 3: Commit (or defer)**

Same one-commit-per-task or single-commit decision as Task 4.1.

### Task 4.3: `AgentSession` accepts new collaborators

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`

- [ ] **Step 1: Update constructor and `run()`**

Replace the existing constructor and the `executor.run(...)` and `advisor.plan(...)` calls:

```kotlin
package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.FfPhase
import knes.agent.perception.RamObserver
import knes.agent.perception.ScreenshotPolicy
import knes.agent.tools.EmulatorToolset
import java.nio.file.Path

data class Budget(
    val maxSkillInvocations: Int = 80,
    val maxAdvisorCalls: Int = 30,
    val costCapUsd: Double = 3.0,
    val wallClockCapSeconds: Int = 900,
)

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

    suspend fun run(): Outcome {
        var previousPhase: FfPhase? = null
        var currentPlan = "Start the game from the title screen and begin a new game."
        var idleTurns = 0
        var lastRam: Map<String, Int> = emptyMap()
        var advisorCalls = 0
        var skillsInvoked = 0
        val startMs = System.currentTimeMillis()

        try {
            while (true) {
                val phase = observer.observe()
                val ram = observer.ramSnapshot()

                val outcome = SuccessCriteria.evaluate(phase)
                if (outcome != Outcome.InProgress) {
                    trace.record(TraceEvent(0, "outcome", phase.toString(), note = outcome.name))
                    return outcome
                }

                val phaseChanged = previousPhase == null || previousPhase!!::class != phase::class
                if (phaseChanged || idleTurns >= 20) {
                    if (++advisorCalls > budget.maxAdvisorCalls) return Outcome.OutOfBudget
                    val attachShot = screenshotPolicy.shouldAttach(previousPhase, phase)
                    val obs = buildString {
                        append("Phase: $phase\nRAM: $ram\n")
                        if (attachShot) append("(screenshot available via getScreen)\n")
                        append("Reason: ${if (phaseChanged) "phase change" else "watchdog stuck"}")
                    }
                    println("[advisor #$advisorCalls] phase=$phase")
                    currentPlan = advisor.plan(phase, obs)
                    println("[advisor plan] ${currentPlan.lineSequence().take(3).joinToString(" | ").take(200)}")
                    trace.record(TraceEvent(0, "advisor", phase.toString(), note = currentPlan.take(500)))
                    idleTurns = 0
                }

                val executorInput = "Plan:\n$currentPlan\n\nCurrent phase: $phase\nRAM: $ram"
                println("[executor turn=$skillsInvoked] phase=$phase idle=$idleTurns")
                val result = executor.run(phase, executorInput)
                skillsInvoked += 1
                println("[executor result] ${result.lineSequence().take(2).joinToString(" | ").take(160)}")
                trace.record(TraceEvent(0, "executor", phase.toString(), note = result.take(500)))

                val newRam = observer.ramSnapshot()
                idleTurns = if (newRam == lastRam) idleTurns + 1 else 0
                lastRam = newRam
                previousPhase = phase

                if (skillsInvoked > budget.maxSkillInvocations) return Outcome.OutOfBudget
                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
                if (elapsedSec > budget.wallClockCapSeconds) return Outcome.OutOfBudget
            }
        } finally {
            trace.close()
        }
    }
}
```

- [ ] **Step 2: Compile (will likely still fail until Main.kt updated in 4.4)**

```bash
./gradlew :knes-agent:compileKotlin
```

### Task 4.4: `Main.kt` rewire

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/Main.kt`

- [ ] **Step 1: Update wiring**

```kotlin
package knes.agent

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.RamObserver
import knes.agent.runtime.AgentSession
import knes.agent.runtime.Budget
import knes.agent.runtime.Outcome
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val rom = args.firstOrNull { it.startsWith("--rom=") }?.removePrefix("--rom=") ?: "roms/ff.nes"
    val profile = args.firstOrNull { it.startsWith("--profile=") }?.removePrefix("--profile=") ?: "ff1"
    val maxSkills = args.firstOrNull { it.startsWith("--max-skill-invocations=") }?.removePrefix("--max-skill-invocations=")?.toIntOrNull() ?: 80
    val costCap = args.firstOrNull { it.startsWith("--cost-cap-usd=") }?.removePrefix("--cost-cap-usd=")?.toDoubleOrNull() ?: 3.0
    val wallCap = args.firstOrNull { it.startsWith("--wall-clock-cap-seconds=") }?.removePrefix("--wall-clock-cap-seconds=")?.toIntOrNull() ?: 900
    val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("ANTHROPIC_API_KEY not set")

    val outcome: Outcome = runBlocking {
        AnthropicSession(key).use { anthropic ->
            val session = EmulatorSession()
            val toolset = EmulatorToolset(session)
            require(toolset.loadRom(rom).ok) { "Failed to load ROM: $rom" }
            require(toolset.applyProfile(profile).ok) { "Failed to apply profile: $profile" }

            val router = ModelRouter()
            val observer = RamObserver(toolset)
            val advisor = AdvisorAgent(anthropic, router, toolset)
            val executor = ExecutorAgent(anthropic, router, toolset, advisor)

            AgentSession(
                toolset = toolset,
                observer = observer,
                executor = executor,
                advisor = advisor,
                budget = Budget(maxSkillInvocations = maxSkills, costCapUsd = costCap, wallClockCapSeconds = wallCap),
            ).run()
        }
    }

    println("OUTCOME: $outcome")
    exitProcess(if (outcome == Outcome.Victory || outcome == Outcome.AtGarlandBattle) 0 else 1)
}
```

- [ ] **Step 2: Build whole tree**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL across all modules. If `Outcome.AtGarlandBattle` does not exist yet, the build fails — Task 5.2 adds it. For an interim green build, comment out the `|| outcome == Outcome.AtGarlandBattle` and add a TODO; revert once 5.2 lands.

- [ ] **Step 3: Run V1 smoke tests (live)**

```bash
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:test --tests "*SmokeTest*"
```

Expected: PASS. The V1 `AnthropicSmokeTest` and `ReactSmokeTest` should still work — they construct their own clients/agents independent of `AnthropicSession` and `ModelRouter`. If a test breaks because it imports a moved type, fix the import.

- [ ] **Step 4: Single combined commit for Phase 4**

```bash
git add knes-agent/src
git commit -m "refactor(agent): V2 pipeline rewire (singleRunStrategy, AnthropicSession, ModelRouter, SkillRegistry)"
```

---

## Phase 5 — Outcome and RAM phases

End-of-phase property: `Outcome.AtGarlandBattle` exists and `SuccessCriteria` returns it for the appropriate `Battle(GARLAND_ID, …, dead=false)`. `RamObserver` distinguishes `NewGameMenu` and `NameEntry` from `TitleOrMenu` based on the empirical signatures recorded in Task 3.1.

### Task 5.1: `Outcome.AtGarlandBattle` + `SuccessCriteria` update

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/Outcome.kt`
- Modify: `knes-agent/src/test/kotlin/knes/agent/runtime/SuccessCriteriaTest.kt`

- [ ] **Step 1: Update `Outcome` enum**

```kotlin
enum class Outcome { InProgress, AtGarlandBattle, Victory, PartyDefeated, OutOfBudget, Error }
```

- [ ] **Step 2: Update `SuccessCriteria.evaluate`**

```kotlin
object SuccessCriteria {
    fun evaluate(phase: FfPhase): Outcome = when (phase) {
        is FfPhase.Battle ->
            if (phase.enemyId == GARLAND_ID) {
                if (phase.enemyDead) Outcome.Victory else Outcome.AtGarlandBattle
            } else Outcome.InProgress
        FfPhase.PartyDefeated -> Outcome.PartyDefeated
        else -> Outcome.InProgress
    }
}
```

- [ ] **Step 3: Add tests**

Append to `SuccessCriteriaTest`:

```kotlin
test("at garland battle when alive") {
    SuccessCriteria.evaluate(FfPhase.Battle(GARLAND_ID, enemyHp = 106, enemyDead = false)) shouldBe Outcome.AtGarlandBattle
}
test("victory when garland slot is dead") {
    SuccessCriteria.evaluate(FfPhase.Battle(GARLAND_ID, enemyHp = 0, enemyDead = true)) shouldBe Outcome.Victory
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :knes-agent:test --tests "*SuccessCriteriaTest*"
```

Expected: all tests pass (3 from V1 + 2 new).

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/Outcome.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/SuccessCriteriaTest.kt
git commit -m "feat(agent): Outcome.AtGarlandBattle + SuccessCriteria update"
```

### Task 5.2: `RamObserver` distinguishes `NewGameMenu` and `NameEntry`

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt`
- Modify: `knes-agent/src/test/kotlin/knes/agent/perception/RamObserverTest.kt`

- [ ] **Step 1: Read empirical RAM signatures from Task 3.1**

```bash
cat docs/superpowers/notes/2026-05-02-ff1-ram-signatures.md
```

Identify the values of `screenState`, `menuCursor`, `bootFlag` at the `AfterFirstStartTap` and `AfterSecondStartTap` snapshots. Those are the V2 signatures.

- [ ] **Step 2: Update `RamObserver.classify`**

Schema (replace the constants below with the empirical values from Step 1; example values shown):

```kotlin
companion object {
    const val SCREEN_STATE_BATTLE = 0x68
    const val SCREEN_STATE_POST_BATTLE = 0x63
    const val BOOT_FLAG_IN_GAME = 0x4D

    // EMPIRICALLY OBSERVED (Task 3.1 snapshot file). Values shown here are placeholders;
    // implementer replaces with the actual hex values from the recorded signatures.
    const val SCREEN_STATE_NEW_GAME_MENU = 0x40   // PLACEHOLDER — confirm
    const val SCREEN_STATE_NAME_ENTRY    = 0x44   // PLACEHOLDER — confirm

    fun classify(ram: Map<String, Int>): FfPhase {
        val bootFlag = ram["bootFlag"]
        if (bootFlag != null && bootFlag != BOOT_FLAG_IN_GAME) {
            return when (ram["screenState"]) {
                SCREEN_STATE_NEW_GAME_MENU -> FfPhase.NewGameMenu
                SCREEN_STATE_NAME_ENTRY -> FfPhase.NameEntry
                else -> FfPhase.TitleOrMenu
            }
        }

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
```

If the signatures recorded in Task 3.1 don't cleanly distinguish `NewGameMenu` from `NameEntry` (e.g. they share the same `screenState`), use `menuCursor` as a secondary discriminator. If neither field works, leave both phases collapsed under `TitleOrMenu` — V2 will work less precisely but still finish; V2.1 can refine. Document the decision in the file's KDoc.

- [ ] **Step 3: Add tests**

```kotlin
test("NewGameMenu when bootFlag != 0x4D and screenState matches NEW_GAME_MENU constant") {
    val ram = mapOf("bootFlag" to 0x00, "screenState" to RamObserver.SCREEN_STATE_NEW_GAME_MENU)
    RamObserver.classify(ram) shouldBe FfPhase.NewGameMenu
}
test("NameEntry when bootFlag != 0x4D and screenState matches NAME_ENTRY constant") {
    val ram = mapOf("bootFlag" to 0x00, "screenState" to RamObserver.SCREEN_STATE_NAME_ENTRY)
    RamObserver.classify(ram) shouldBe FfPhase.NameEntry
}
test("TitleOrMenu when bootFlag != 0x4D and screenState matches nothing") {
    val ram = mapOf("bootFlag" to 0x00, "screenState" to 0xFF)
    RamObserver.classify(ram) shouldBe FfPhase.TitleOrMenu
}
```

- [ ] **Step 4: Run all `:knes-agent:test`**

```bash
./gradlew :knes-agent:test
```

Expected: V1 tests still pass + 3 new tests pass.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt \
        knes-agent/src/test/kotlin/knes/agent/perception/RamObserverTest.kt
git commit -m "feat(agent): RamObserver detects NewGameMenu and NameEntry phases"
```

---

## Phase 6 — Cost tracking and budget enforcement

End-of-phase property: trace records per-turn token counts and estimated USD; budget caps in `AgentSession` actually trigger `OutOfBudget` when violated.

### Task 6.1: `CostTracker`

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/runtime/CostTracker.kt`
- Create: `knes-agent/src/test/kotlin/knes/agent/runtime/CostTrackerTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package knes.agent.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class CostTrackerTest : FunSpec({
    test("Sonnet 4.5 input + output cost") {
        val t = CostTracker()
        t.add(AnthropicModels.Sonnet_4_5, inputTokens = 1000, outputTokens = 200, cachedInputTokens = 0)
        // $3 / MTok input + $15 / MTok output = 0.001*3 + 0.0002*15 = 0.003 + 0.003 = 0.006
        t.totalUsd shouldBe 0.006
    }
    test("cached input billed at 10%") {
        val t = CostTracker()
        t.add(AnthropicModels.Sonnet_4_5, inputTokens = 0, outputTokens = 0, cachedInputTokens = 1000)
        // $3 / MTok × 0.10 × 1000/1e6 = 0.0003
        t.totalUsd shouldBe 0.0003
    }
    test("Haiku 4.5 cheaper than Sonnet") {
        val haiku = CostTracker()
        haiku.add(AnthropicModels.Haiku_4_5, inputTokens = 1000, outputTokens = 200)
        val sonnet = CostTracker()
        sonnet.add(AnthropicModels.Sonnet_4_5, inputTokens = 1000, outputTokens = 200)
        sonnet.totalUsd shouldBeGreaterThan haiku.totalUsd
    }
})
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew :knes-agent:test --tests "*CostTrackerTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package knes.agent.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel

/**
 * Cumulative Anthropic token-cost tracker. Prices per spec §7 (Anthropic pricing page,
 * 2026-05). Cached input tokens are billed at 10% of base input rate.
 */
class CostTracker {
    private data class Pricing(val inputPerMTok: Double, val outputPerMTok: Double)

    private val table: Map<LLModel, Pricing> = mapOf(
        AnthropicModels.Haiku_4_5 to Pricing(1.0, 5.0),
        AnthropicModels.Sonnet_4_5 to Pricing(3.0, 15.0),
        AnthropicModels.Opus_4 to Pricing(15.0, 75.0),
        AnthropicModels.Opus_4_1 to Pricing(15.0, 75.0),
    )

    var totalUsd: Double = 0.0
        private set

    fun add(model: LLModel, inputTokens: Int = 0, outputTokens: Int = 0, cachedInputTokens: Int = 0) {
        val p = table[model] ?: return
        val inUsd = inputTokens / 1_000_000.0 * p.inputPerMTok
        val cachedUsd = cachedInputTokens / 1_000_000.0 * p.inputPerMTok * 0.10
        val outUsd = outputTokens / 1_000_000.0 * p.outputPerMTok
        totalUsd += inUsd + cachedUsd + outUsd
    }
}
```

- [ ] **Step 4: Run, expect green**

```bash
./gradlew :knes-agent:test --tests "*CostTrackerTest*"
```

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/runtime/CostTracker.kt \
        knes-agent/src/test/kotlin/knes/agent/runtime/CostTrackerTest.kt
git commit -m "feat(agent): CostTracker (per-model USD accumulation)"
```

### Task 6.2: Wire `CostTracker` into `AgentSession`

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/Trace.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt`

The Anthropic SDK exposes per-call token counts via `ResponseMetaInfo` on the assistant message. Koog's `AIAgent.run` returns the message string; to get tokens we need to either intercept the executor's response or wrap `SingleLLMPromptExecutor`.

- [ ] **Step 1: Probe Koog for usage hooks**

```bash
JAR=$(find ~/.gradle/caches -path '*ai/koog/prompt-executor-llms*0.5.1*' -name '*.jar' | head -1)
unzip -l "$JAR" | grep -iE 'Usage|Meta|Listener' | head
```

If Koog exposes a usage callback or pipeline interceptor: use it. If not (likely): wrap `SingleLLMPromptExecutor` in a thin decorator that captures `ResponseMetaInfo` from the underlying `AnthropicLLMClient`. Place wrapper in `knes-agent/src/main/kotlin/knes/agent/llm/UsageTrackingExecutor.kt` and have `AnthropicSession.executor` use it.

- [ ] **Step 2: Capture tokens at the executor boundary**

In `AnthropicSession`, replace:

```kotlin
val executor: SingleLLMPromptExecutor = SingleLLMPromptExecutor(client)
```

with a tracking variant; expose the tracker:

```kotlin
val tracker: CostTracker = CostTracker()

val executor: SingleLLMPromptExecutor = run {
    // If Koog has a hook, use it. Otherwise this is the simplest fallback: wrap
    // AnthropicLLMClient.execute and intercept ResponseMetaInfo on each return,
    // then forward to SingleLLMPromptExecutor.
    SingleLLMPromptExecutor(UsageTrackingClient(client, tracker))
}
```

Where `UsageTrackingClient` is a thin adapter that delegates to `AnthropicLLMClient.execute` and, on each response, calls `tracker.add(model, inputTokens, outputTokens, cachedInputTokens)` reading `ResponseMetaInfo`.

If implementing this wrapper would require reflection or copying significant Koog code, fall back: just record tokens manually in `Trace` using `ResponseMetaInfo` exposed on whatever Koog returns (if it does). If Koog hides it entirely, log a TODO and move on — V2's cost budget then becomes a soft estimate based on per-turn defaults (`Sonnet ~ 1500 in / 200 out`).

- [ ] **Step 3: Update `AgentSession` to enforce `costCapUsd`**

In `AgentSession.run()`, after each executor / advisor call:

```kotlin
val costNow = anthropic.tracker.totalUsd  // requires AnthropicSession injected; add to constructor
if (costNow > budget.costCapUsd) return Outcome.OutOfBudget
```

Add `private val anthropic: AnthropicSession` to the constructor. `Main.kt` already has the `AnthropicSession` — pass it in:

```kotlin
AgentSession(
    anthropic = anthropic,
    toolset = toolset,
    ...
)
```

- [ ] **Step 4: Build + test**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src
git commit -m "feat(agent): wire CostTracker through AnthropicSession into AgentSession budget"
```

---

## Phase 7 — Acceptance run

End-of-phase property: a recorded successful run reaching `Outcome.AtGarlandBattle`, plus the trace and cost summary committed as evidence under `docs/superpowers/runs/`.

### Task 7.1: Headless dry-run, no key

**Files:** none (smoke check).

- [ ] **Step 1: Confirm graceful fail without API key**

```bash
unset ANTHROPIC_API_KEY
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes --profile=ff1"
```

Expected: exit non-zero with `ANTHROPIC_API_KEY not set` — confirms the run path triggers our error message before doing any expensive work.

### Task 7.2: Acceptance run with API key

- [ ] **Step 1: Run**

```bash
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY ./gradlew :knes-agent:run \
  --args="--rom=$PWD/roms/ff.nes --profile=ff1 --max-skill-invocations=80 --cost-cap-usd=3 --wall-clock-cap-seconds=900"
```

Expected: terminal logs phase transitions; final line `OUTCOME: AtGarlandBattle`; exit code 0.

If the run fails:

- **`OUTCOME: PartyDefeated`** before reaching Garland → check trace; usually means a random encounter wiped the party. Consider lowering encounter rate (FF1 has a hidden RNG; can't easily control), or have the executor invoke `battleFightAll` more aggressively. Iterate prompt / skill order, re-run.
- **`OUTCOME: OutOfBudget`** → check whether budget hit was `cost`, `wall-clock`, or `skill count`. If skill count: bump `--max-skill-invocations`. If cost: caching may not be working — revisit Task 1.4 path B.
- **Crash** → fix and re-run; this is real iteration.

If `GARLAND_ID = 0x7C` is wrong, the run may say `OUTCOME: OutOfBudget` while the trace shows the agent reached `Battle(enemyId=…)` with a different id. Update `GARLAND_ID` in `Outcome.kt` and re-run.

- [ ] **Step 2: Capture evidence**

```bash
mkdir -p docs/superpowers/runs/2026-05-02-v2-acceptance
cp runs/<timestamp>/trace.jsonl docs/superpowers/runs/2026-05-02-v2-acceptance/trace.jsonl
echo "OUTCOME: AtGarlandBattle" > docs/superpowers/runs/2026-05-02-v2-acceptance/SUMMARY.md
echo "Cost: $X" >> docs/superpowers/runs/2026-05-02-v2-acceptance/SUMMARY.md
echo "Wall clock: $Y seconds" >> docs/superpowers/runs/2026-05-02-v2-acceptance/SUMMARY.md
git add docs/superpowers/runs/2026-05-02-v2-acceptance/
git commit -m "evidence: V2 acceptance run reaches Garland battle"
```

- [ ] **Step 3: Push branch + open PR**

```bash
git push -u origin ff1-agent-v2
gh pr create --repo ArturSkowronski/kNES --base master --head ff1-agent-v2 \
  --title "FF1 agent V2 — singleRunStrategy + skill library + caching, reaches Garland" \
  --body-file docs/superpowers/specs/2026-05-01-ff1-koog-agent-v2-design.md
```

(The PR body cites the spec; expand the body manually with V2 acceptance evidence after the auto-fill.)

---

## Self-review notes

**Spec coverage:**
- §3 architecture → Tasks 1.2–1.4 (llm/), 2.1–3.4 (skills/), 4.1–4.4 (rewire), 6.1–6.2 (runtime)
- §4 singleRunStrategy → Tasks 4.1, 4.2
- §5 skill library → Phase 2 + Phase 3
- §6 prompt caching → Task 1.1 (probe) + 1.2 (long-lived client) + 1.4 (config)
- §7 model routing → Task 1.3
- §8 phase classification → Tasks 1.3 (type), 5.2 (RamObserver)
- §9 Outcome.AtGarlandBattle → Task 5.1
- §10 budgets → Phase 6, Task 4.4 (CLI flags)
- §11 risks: Koog API uncertainty handled in Task 1.1 + 4.1 step 1 + 6.2 step 1; empirical RAM signatures via Task 3.1; GARLAND_ID confirmed in 7.2.
- §13 acceptance test → Phase 7
- §12 path to V3 → not in V2 plan (correct — V2.1+ are separate plans)

**Type consistency:**
- `Skill.invoke(args: Map<String, String>)` consistent across §5 sketches and Tasks 2.1, 2.2, 3.2, 3.3.
- `SkillResult(ok, message, framesElapsed, ramAfter)` consistent.
- `AnthropicSession(apiKey)` constructor consistent across Tasks 1.2, 4.4.
- `ModelRouter.modelFor(phase, role)` consistent across Tasks 1.3, 4.1, 4.2.
- `AdvisorAgent.plan(phase, observation)` consistent across Tasks 4.1 (definition), 4.2 (`AdvisorToolset` caller), 4.3 (`AgentSession` caller).
- `ExecutorAgent.run(phase, input)` consistent across Tasks 4.2, 4.3.
- `Outcome.AtGarlandBattle` introduced in 5.1, referenced in 4.4 (Main.kt exit code) — note Task 4.4 calls out the temporary TODO if 5.1 hasn't landed yet.

**Placeholder scan:**
- §4 Task 5.2 `SCREEN_STATE_NEW_GAME_MENU = 0x40 // PLACEHOLDER` — explicitly documented as "implementer replaces with empirical value from Task 3.1". This is not a forbidden placeholder; it's a known-empirical-value-needed-from-prior-task.
- No `TODO`, `TBD`, `implement later`, or vague "add error handling" left in the plan.

**Open known-knowns:**
- Task 1.1's findings can flip Task 1.4 between Path A and Path B. Both paths have full code in this plan.
- Task 6.2's Step 1 probe may force a fall-back to estimated tokens; explicitly handled.
- Task 3.2 (`CreateDefaultParty`) and Task 3.3 (`WalkOverworldTo`) are scripts against a real ROM — likely require one iteration after Task 3.1 evidence is read. Plan calls this out at Task 3.2 Step 4.
