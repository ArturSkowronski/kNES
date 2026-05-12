# knes-agent v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `knes.agent.v2.*` — a parallel FF1 agent implementing the Cartographer/Advisor/Executor/Reviewer architecture from `docs/Gemini Plays Final Fantasy.pdf`, runnable side-by-side with v1 (`knes.agent.*`).

**Architecture:** Four cooperating roles (Cartographer = pre-run vision exploration; Advisor = Gemini 3.1 Pro plan writer; Executor = Sonnet 4.6 per-turn tool picker; Reviewer = Haiku 4.5 audit) coordinated by a phase-aware stuck-signal watchdog. Per-run memory namespace under `~/.knes/runs/<ts>-v2/`, hierarchical decision-log save format, slim composable tool surface (3 verbs + 4 proven macros reused from v1).

**Tech Stack:** Kotlin 2.3, Koog (`ai.koog:agents-*:0.6.1`), kotlinx-serialization-json, kotlinx-coroutines, existing `knes-agent-tools`, `knes-emulator-session`. Models: Gemini 3.1 Pro (vision + planner), Claude Sonnet 4.6 (`claude-sonnet-4-6`) executor, Claude Haiku 4.5 (`claude-haiku-4-5-20251001`) reviewer.

**Spec:** `docs/superpowers/specs/2026-05-12-knes-agent-v2-design.md`

---

## File map

**Create (all under `knes-agent/src/main/kotlin/knes/agent/v2/`):**

| File | Responsibility |
|---|---|
| `Main.kt` | `runV2` entry point; arg parsing; bootstrap + campaign loop |
| `V2Config.kt` | parsed CLI args (`--rom`, `--fresh`, `--resume`, `--gemini-key`, `--max-turns`) |
| `runtime/V2RunDirectory.kt` | builds `~/.knes/runs/<ts>-v2/` dir structure; symlink `latest-v2` |
| `runtime/V2Memory.kt` | atomic JSON read/write for `campaign.json`, `current_plan.json`, `decisions/turn-*.json`, `landmarks.json`, etc. |
| `runtime/Campaign.kt` | data classes for `campaign.json` (Milestone, PlanEntry, ReviewEntry) |
| `runtime/Plan.kt` | data class for `current_plan.json` (numbered PlanStep list) |
| `runtime/TurnLog.kt` | data class for `decisions/turn-NNNN.json` |
| `runtime/Watchdog.kt` | deterministic phase-aware stuck detector |
| `runtime/SnapshotDumper.kt` | writes `snapshots/turn-NNNN.png`, idempotent |
| `runtime/Phase.kt` | enum (Boot/Overworld/Indoors/Battle/MenuStuck/Dialog/BattleMessage/Cutscene/CartographerExplore) + classifier reusing v1 `FfPhase` + vision |
| `tools/ToolSurface.kt` | dispatcher: `walkTo`, `interactAt`, `useMenu`, plus 4 macro wrappers |
| `tools/MenuWalker.kt` | parses path strings (`"main/equip/char1/weapon/0"`) into button sequence |
| `agents/CartographerAgent.kt` | Gemini 3.1 Pro online vision exploration |
| `agents/AdvisorAgent.kt` | Gemini 3.1 Pro planner; writes `current_plan.json` + appends to `campaign.json` |
| `agents/ExecutorAgent.kt` | Sonnet 4.6 per-turn tool picker |
| `agents/ReviewerAgent.kt` | Haiku 4.5 audit; mutates Memory only via `remove`; writes `review.jsonl` + `cartographer-flags.json` |
| `llm/GeminiPro31Client.kt` | thin wrapper around Gemini REST (`gemini-3.1-pro`); reuse `GeminiVisionConsult` pattern |
| `llm/SonnetClient.kt` | reuse v1 `AnthropicSession`, pin model id `claude-sonnet-4-6` |
| `llm/HaikuClient.kt` | reuse v1 `AnthropicSession`, pin model id `claude-haiku-4-5-20251001` |

**Test (under `knes-agent/src/test/kotlin/knes/agent/v2/`):**

| File | Tests |
|---|---|
| `runtime/WatchdogTest.kt` | phase-aware threshold, stuck counter, whitelist |
| `runtime/V2MemoryTest.kt` | atomic write + resume round-trip |
| `tools/MenuWalkerTest.kt` | path parsing + button sequence emission |

**Modify:**
- `knes-agent/build.gradle` — add `runV2` gradle task (custom JavaExec)

---

## Conventions

- **Kotlin style:** match v1 (`knes.agent.*`) — `data class` for JSON, suspend functions for agent calls, `runBlocking` only in Main.
- **JSON I/O:** kotlinx-serialization-json with `prettyPrint = true`; atomic write = write to `*.tmp` then `Files.move`. Reuse pattern from `knes.agent.perception.AtomicJsonWriter` if accessible from v2 package (verify in Task A2).
- **Logging:** stderr `[v2.<component>]` prefix.
- **Commits:** after every passing step, with message `feat(v2): <task summary>` or `test(v2): <test summary>`.

---

## Phase A — Foundations (deterministic, no LLM)

### Task A1: Scaffold `knes.agent.v2` package + `runV2` gradle task

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/V2Config.kt`
- Modify: `knes-agent/build.gradle`

- [ ] **Step 1: Create `V2Config.kt` with arg parser**

```kotlin
package knes.agent.v2

import java.nio.file.Path

data class V2Config(
    val rom: String,
    val profile: String,
    val resumeDir: Path?,
    val fresh: Boolean,
    val maxTurns: Int,
    val cartographerBudgetSeconds: Int,
    val cartographerMaxVisionCalls: Int,
) {
    companion object {
        fun parse(args: Array<String>): V2Config {
            fun arg(prefix: String) = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            val resume = arg("--resume=")?.let(Path::of)
            val fresh = args.contains("--fresh") || resume == null
            return V2Config(
                rom = arg("--rom=") ?: "roms/ff.nes",
                profile = arg("--profile=") ?: "ff1",
                resumeDir = resume,
                fresh = fresh,
                maxTurns = arg("--max-turns=")?.toInt() ?: 5000,
                cartographerBudgetSeconds = arg("--cart-seconds=")?.toInt() ?: 600,
                cartographerMaxVisionCalls = arg("--cart-vision-calls=")?.toInt() ?: 60,
            )
        }
    }
}
```

- [ ] **Step 2: Create stub `Main.kt`**

```kotlin
package knes.agent.v2

fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    System.err.println("[v2.main] config=$cfg")
    System.err.println("[v2.main] not yet implemented")
}
```

- [ ] **Step 3: Add `runV2` task in `knes-agent/build.gradle`**

Modify the `application { ... }` block area. After the existing `application { mainClass = 'knes.agent.MainKt' }`, append:

```groovy
tasks.register('runV2', JavaExec) {
    group = 'application'
    description = 'Run knes-agent v2 (PDF architecture)'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'knes.agent.v2.MainKt'
    standardInput = System.in
    if (project.hasProperty('appArgs')) {
        args(project.property('appArgs').toString().split(' '))
    }
}
```

- [ ] **Step 4: Verify build + smoke run**

Run: `./gradlew :knes-agent:runV2 --quiet`
Expected stderr: `[v2.main] config=V2Config(rom=roms/ff.nes, ...)` then `[v2.main] not yet implemented`. Exit 0.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/build.gradle knes-agent/src/main/kotlin/knes/agent/v2/
git commit -m "feat(v2): scaffold knes.agent.v2 package + runV2 gradle task"
```

---

### Task A2: V2RunDirectory + directory layout

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/V2RunDirectory.kt`

- [ ] **Step 1: Implement `V2RunDirectory`**

```kotlin
package knes.agent.v2.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class V2RunDirectory(val root: Path) {
    val campaignJson: Path = root.resolve("campaign.json")
    val currentPlanJson: Path = root.resolve("current_plan.json")
    val landmarksJson: Path = root.resolve("landmarks.json")
    val warpsJson: Path = root.resolve("warps.json")
    val blockagesJson: Path = root.resolve("blockages.json")
    val overworldMapJson: Path = root.resolve("overworld-map.json")
    val cartographerFlagsJson: Path = root.resolve("cartographer-flags.json")
    val reviewJsonl: Path = root.resolve("review.jsonl")
    val decisionsDir: Path = root.resolve("decisions")
    val snapshotsDir: Path = root.resolve("snapshots")
    val interiorMapsDir: Path = root.resolve("interior-maps")
    val savestateDir: Path = root.resolve("savestate-checkpoints")

    fun ensure() {
        root.createDirectories()
        decisionsDir.createDirectories()
        snapshotsDir.createDirectories()
        interiorMapsDir.createDirectories()
        savestateDir.createDirectories()
    }

    fun turnDecisionFile(turn: Int): Path =
        decisionsDir.resolve("turn-%05d.json".format(turn))

    fun turnSnapshot(turn: Int): Path =
        snapshotsDir.resolve("turn-%05d.png".format(turn))

    fun savestate(turn: Int): Path =
        savestateDir.resolve("T%d.nss".format(turn))

    companion object {
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
        private fun runsRoot(): Path =
            Path.of(System.getProperty("user.home"), ".knes", "runs")

        fun freshRun(): V2RunDirectory {
            val ts = LocalDateTime.now().format(TS_FMT)
            val dir = runsRoot().resolve("$ts-v2")
            val rd = V2RunDirectory(dir).also { it.ensure() }
            updateLatestSymlink(dir)
            return rd
        }

        fun resume(path: Path): V2RunDirectory {
            require(path.exists()) { "resume dir does not exist: $path" }
            return V2RunDirectory(path)
        }

        private fun updateLatestSymlink(target: Path) {
            val link = runsRoot().resolve("latest-v2")
            try {
                link.deleteIfExists()
                Files.createSymbolicLink(link, target)
            } catch (e: Throwable) {
                System.err.println("[v2.run-dir] WARN: symlink update failed: ${e.message}")
            }
        }
    }
}
```

- [ ] **Step 2: Wire into `Main.kt`**

Replace stub Main body:

```kotlin
fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    val run = if (cfg.resumeDir != null) V2RunDirectory.resume(cfg.resumeDir)
             else V2RunDirectory.freshRun()
    System.err.println("[v2.main] run dir: ${run.root}")
}
```

Add import: `import knes.agent.v2.runtime.V2RunDirectory`.

- [ ] **Step 3: Verify**

Run: `./gradlew :knes-agent:runV2 --quiet`
Expected: stderr `[v2.main] run dir: /Users/.../.knes/runs/2026-05-12-NNNN-v2`. The directory exists with subdirs `decisions/`, `snapshots/`, `interior-maps/`, `savestate-checkpoints/`.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/
git commit -m "feat(v2): V2RunDirectory + per-run namespace under ~/.knes/runs/"
```

---

### Task A3: Campaign / Plan / TurnLog data classes

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/Campaign.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/Plan.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/TurnLog.kt`

- [ ] **Step 1: `Campaign.kt`**

```kotlin
package knes.agent.v2.runtime

import kotlinx.serialization.Serializable

@Serializable
data class Campaign(
    val startedAt: String,
    val scope: String,
    val milestones: MutableList<Milestone> = mutableListOf(),
    val plans: MutableList<PlanEntry> = mutableListOf(),
    val reviews: MutableList<ReviewEntry> = mutableListOf(),
    var lastTurn: Int = 0,
    var done: Boolean = false,
)

@Serializable
data class Milestone(
    val id: String,
    var status: String,        // "pending" | "in_progress" | "done"
    var turnStart: Int? = null,
    var turnEnd: Int? = null,
    var planStep: Int? = null,
)

@Serializable
data class PlanEntry(
    val turn: Int,
    val by: String,             // "advisor"
    val summary: String,
    val snapshot: String,       // relative path
    val reason: String? = null, // "stuck-signal" | "T0 fresh" | ...
)

@Serializable
data class ReviewEntry(
    val turn: Int,
    val removed: List<String>,
    val flagged: List<String>,
)
```

- [ ] **Step 2: `Plan.kt`**

```kotlin
package knes.agent.v2.runtime

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val createdAtTurn: Int,
    val milestone: String,
    val steps: List<PlanStep>,
    var cursor: Int = 0,        // index of current step
)

@Serializable
data class PlanStep(
    val index: Int,
    val description: String,
    val intentTool: String? = null,   // optional hint, e.g. "walkTo"
    val intentArgs: Map<String, String>? = null,
)
```

- [ ] **Step 3: `TurnLog.kt`**

```kotlin
package knes.agent.v2.runtime

import kotlinx.serialization.Serializable

@Serializable
data class TurnLog(
    val turn: Int,
    val frame: Long,
    val phase: String,
    val ram: Map<String, Int>,
    val snapshot: String,
    val executor: ExecutorTrace,
    val watchdog: WatchdogTrace,
)

@Serializable
data class ExecutorTrace(
    val model: String,
    val tool: String,
    val args: Map<String, String>,
    val reasoningSummary: String,
    val outcome: String,        // "ok" | "fail" | "reject"
    val message: String? = null,
    val ms: Long,
)

@Serializable
data class WatchdogTrace(
    val stuckCounter: Int,
    val threshold: Int,
)
```

- [ ] **Step 4: Compile check**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/runtime/
git commit -m "feat(v2): Campaign/Plan/TurnLog data classes"
```

---

### Task A4: V2Memory (atomic JSON I/O)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/V2Memory.kt`

- [ ] **Step 1: Implement `V2Memory`**

```kotlin
package knes.agent.v2.runtime

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import kotlin.io.path.exists
import kotlin.io.path.readText

class V2Memory(val run: V2RunDirectory) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    var campaign: Campaign = loadOrInitCampaign()
        private set

    var currentPlan: Plan? = loadPlanIfExists()
        private set

    fun saveCampaign() {
        atomicWrite(run.campaignJson, json.encodeToString(Campaign.serializer(), campaign))
    }

    fun setPlan(plan: Plan) {
        currentPlan = plan
        atomicWrite(run.currentPlanJson, json.encodeToString(Plan.serializer(), plan))
    }

    fun appendTurn(log: TurnLog) {
        val out = json.encodeToString(TurnLog.serializer(), log)
        atomicWrite(run.turnDecisionFile(log.turn), out)
        campaign.lastTurn = log.turn
        saveCampaign()
    }

    fun appendReviewLine(line: String) {
        Files.writeString(
            run.reviewJsonl, line + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private fun loadOrInitCampaign(): Campaign =
        if (run.campaignJson.exists()) {
            json.decodeFromString(Campaign.serializer(), run.campaignJson.readText())
        } else {
            Campaign(
                startedAt = OffsetDateTime.now().toString(),
                scope = "coneria_buy_equip_grind",
            ).also { c ->
                atomicWrite(run.campaignJson, json.encodeToString(Campaign.serializer(), c))
            }
        }

    private fun loadPlanIfExists(): Plan? =
        if (run.currentPlanJson.exists()) json.decodeFromString(
            Plan.serializer(), run.currentPlanJson.readText()
        ) else null

    private fun atomicWrite(path: Path, content: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/runtime/V2Memory.kt
git commit -m "feat(v2): V2Memory atomic JSON I/O"
```

---

### Task A5: V2Memory round-trip test (TEST 1 of 3)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/v2/runtime/V2MemoryTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package knes.agent.v2.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files

class V2MemoryTest : StringSpec({
    "campaign + plan + turn-log survive write + reopen" {
        val tmpRoot = Files.createTempDirectory("v2-mem-test")
        val run = V2RunDirectory(tmpRoot).also { it.ensure() }

        val m1 = V2Memory(run)
        m1.campaign.milestones += Milestone(id = "boot", status = "done", turnStart = 1, turnEnd = 47)
        m1.campaign.plans += PlanEntry(turn = 48, by = "advisor", summary = "head to (15,22)", snapshot = "snapshots/turn-00048.png")
        m1.saveCampaign()

        val plan = Plan(
            createdAtTurn = 48,
            milestone = "buy_weapons",
            steps = listOf(PlanStep(0, "walk to weapon shop", "walkTo", mapOf("x" to "15", "y" to "22"))),
        )
        m1.setPlan(plan)

        val turnLog = TurnLog(
            turn = 49, frame = 1234L, phase = "Overworld",
            ram = mapOf("worldX" to 14, "worldY" to 21),
            snapshot = "snapshots/turn-00049.png",
            executor = ExecutorTrace("claude-sonnet-4-6", "walkTo", mapOf("x" to "15", "y" to "22"), "step 0", "ok", null, 200),
            watchdog = WatchdogTrace(0, 5),
        )
        m1.appendTurn(turnLog)

        // Reopen
        val m2 = V2Memory(V2RunDirectory(tmpRoot))
        m2.campaign.milestones.map { it.id } shouldContainExactly listOf("boot")
        m2.campaign.lastTurn shouldBe 49
        m2.currentPlan?.steps?.size shouldBe 1
        m2.currentPlan?.steps?.first()?.intentArgs?.get("x") shouldBe "15"
    }
})
```

- [ ] **Step 2: Run — verify fails first**

Run: `./gradlew :knes-agent:test --tests 'knes.agent.v2.runtime.V2MemoryTest' --quiet`
Expected initially: if there's a typo/missing wiring → FAIL. Fix until PASS.

- [ ] **Step 3: Verify PASS**

Run again. Expected: green.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/v2/runtime/V2MemoryTest.kt
git commit -m "test(v2): V2Memory atomic write + reopen round-trip"
```

---

### Task A6: Phase enum + classifier wrapper

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/Phase.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.runtime

enum class Phase {
    Boot, Overworld, Indoors, Battle, MenuStuck,
    Dialog, BattleMessage, Cutscene, CartographerExplore;

    companion object {
        /**
         * Classify from RAM. Reuses v1 phase markers; CartographerExplore is
         * set explicitly by Cartographer agent — never inferred here.
         */
        fun fromRam(ram: Map<String, Int>): Phase {
            val mapId = ram["currentMapId"] ?: -1
            val mapflags = ram["mapflags"] ?: 0
            val battleInProgress = (ram["battleState"] ?: 0) != 0
            val menuState = ram["menuState"] ?: 0
            val party = (ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0
            return when {
                !party -> Boot
                battleInProgress -> Battle
                menuState != 0 -> MenuStuck
                mapId == 0 && (mapflags and 1) == 0 -> Overworld
                else -> Indoors
            }
        }
    }
}

/** RAM fields stable across phase whitelist (used by Watchdog for "static is OK"). */
val PHASE_STATIC_WHITELIST: Set<Phase> = setOf(Phase.Dialog, Phase.BattleMessage, Phase.Cutscene)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/runtime/Phase.kt
git commit -m "feat(v2): Phase enum + RAM-based classifier"
```

---

### Task A7: Watchdog (deterministic)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/Watchdog.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.runtime

class Watchdog(
    private val thresholds: Map<Phase, Int> = DEFAULT_THRESHOLDS,
    private val staticWhitelist: Set<Phase> = PHASE_STATIC_WHITELIST,
) {
    private var stuck = 0
    private var lastRamHash: Int = 0

    /**
     * @param phase  current Phase classification
     * @param ramHash hash of watched RAM fields (worldX, worldY, currentMapId, hp..., gold, menuState)
     * @param skillProgress true if last executor outcome was Ok
     */
    fun observe(phase: Phase, ramHash: Int, skillProgress: Boolean) {
        if (phase in staticWhitelist) return
        stuck = if (ramHash == lastRamHash && !skillProgress) stuck + 1 else 0
        lastRamHash = ramHash
    }

    fun stuckSignal(phase: Phase): Boolean =
        stuck >= (thresholds[phase] ?: 5)

    fun threshold(phase: Phase): Int = thresholds[phase] ?: 5
    fun counter(): Int = stuck
    fun reset() { stuck = 0 }

    fun diagnose(phase: Phase, recentOutcomes: List<String>): String =
        "phase=$phase stuck=$stuck/${threshold(phase)} recentOutcomes=${recentOutcomes.takeLast(3)}"

    companion object {
        val DEFAULT_THRESHOLDS: Map<Phase, Int> = mapOf(
            Phase.Battle to 3, Phase.MenuStuck to 3,
            Phase.Overworld to 5, Phase.Indoors to 5,
            Phase.CartographerExplore to 10,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/runtime/Watchdog.kt
git commit -m "feat(v2): Watchdog phase-aware stuck-signal"
```

---

### Task A8: Watchdog test (TEST 2 of 3)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/v2/runtime/WatchdogTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package knes.agent.v2.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WatchdogTest : StringSpec({
    "Overworld threshold is 5, MenuStuck is 3" {
        val w = Watchdog()
        // Five identical RAM hashes + zero skill progress in Overworld → signal
        repeat(5) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.Overworld) shouldBe true
        w.reset()
        // Three is enough for MenuStuck
        repeat(3) { w.observe(Phase.MenuStuck, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.MenuStuck) shouldBe true
    }

    "skill progress resets counter" {
        val w = Watchdog()
        repeat(4) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.observe(Phase.Overworld, ramHash = 42, skillProgress = true)
        w.stuckSignal(Phase.Overworld) shouldBe false
        w.counter() shouldBe 0
    }

    "Dialog whitelist does not tick counter even with static RAM" {
        val w = Watchdog()
        repeat(20) { w.observe(Phase.Dialog, ramHash = 42, skillProgress = false) }
        w.stuckSignal(Phase.Dialog) shouldBe false
        w.counter() shouldBe 0
    }

    "RAM change resets counter" {
        val w = Watchdog()
        repeat(4) { w.observe(Phase.Overworld, ramHash = 42, skillProgress = false) }
        w.observe(Phase.Overworld, ramHash = 99, skillProgress = false)
        w.counter() shouldBe 0
    }
})
```

- [ ] **Step 2: Run — verify it executes**

Run: `./gradlew :knes-agent:test --tests 'knes.agent.v2.runtime.WatchdogTest' --quiet`
Expected: green (or failures iff Watchdog logic is wrong — fix Watchdog).

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/v2/runtime/WatchdogTest.kt
git commit -m "test(v2): Watchdog phase thresholds + whitelist"
```

---

### Task A9: SnapshotDumper

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/SnapshotDumper.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.runtime

import knes.agent.tools.EmulatorToolset
import java.nio.file.Files
import java.util.Base64

class SnapshotDumper(
    private val toolset: EmulatorToolset,
    private val run: V2RunDirectory,
) {
    /** Dump per-iter screenshot. Idempotent — overwrites if same turn called twice. */
    fun dump(turn: Int): String {
        val b64 = toolset.getScreen().base64
        val bytes = Base64.getDecoder().decode(b64)
        val out = run.turnSnapshot(turn)
        Files.write(out, bytes)
        return run.root.relativize(out).toString()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/runtime/SnapshotDumper.kt
git commit -m "feat(v2): SnapshotDumper — per-iter PNG dump"
```

---

## Phase B — Tool surface

### Task B1: MenuWalker (deterministic FF1 menu path parser)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/tools/MenuWalker.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.tools

/**
 * Parses menu path strings like "main/equip/char1/weapon/0" into a sequence
 * of NES button taps for the FF1 field menu.
 *
 * Path grammar:
 *   <root> "/" <segment> ("/" <segment>)*
 *   root := main | shop
 *   main segments := item|magic|equip|status|exit|char1..char4|weapon|armor|<slot-index>
 *   shop segments := buy|sell|exit|<item-index>|char1..char4
 *
 * The walker emits an ordered list of MenuTap actions; an executor calls
 * `toolset.tap(button, ...)` for each. MenuWalker itself is pure (no I/O),
 * tested below.
 */
data class MenuTap(val button: String, val count: Int = 1)

class MenuWalker {
    fun parse(path: String): List<MenuTap> {
        val segments = path.trim('/').split("/")
        require(segments.isNotEmpty()) { "empty menu path" }
        return when (segments[0]) {
            "main" -> parseMain(segments.drop(1))
            "shop" -> parseShop(segments.drop(1))
            else -> throw IllegalArgumentException("unknown menu root: ${segments[0]}")
        }
    }

    private fun parseMain(rest: List<String>): List<MenuTap> {
        val out = mutableListOf<MenuTap>()
        out += MenuTap("B")    // open field menu
        if (rest.isEmpty()) return out
        val top = mapOf("item" to 0, "magic" to 1, "equip" to 2, "status" to 3, "exit" to 4)
        val idx = top[rest[0]] ?: throw IllegalArgumentException("unknown main item: ${rest[0]}")
        if (idx > 0) out += MenuTap("DOWN", idx)
        out += MenuTap("A")
        // char selector
        if (rest.size >= 2 && rest[1].startsWith("char")) {
            val n = rest[1].removePrefix("char").toInt()
            require(n in 1..4) { "char must be 1..4" }
            if (n > 1) out += MenuTap("DOWN", n - 1)
            out += MenuTap("A")
        }
        // weapon|armor sub-tab (equip only)
        if (rest.size >= 3 && (rest[2] == "weapon" || rest[2] == "armor")) {
            if (rest[2] == "armor") out += MenuTap("RIGHT")
            // slot
            if (rest.size >= 4) {
                val slot = rest[3].toInt()
                require(slot in 0..3) { "slot must be 0..3" }
                if (slot > 0) out += MenuTap("DOWN", slot)
                out += MenuTap("A")
            }
        }
        return out
    }

    private fun parseShop(rest: List<String>): List<MenuTap> {
        // BUY/SELL/EXIT menu reached via dialog A-tap by caller
        val out = mutableListOf<MenuTap>()
        if (rest.isEmpty()) return out
        val top = mapOf("buy" to 0, "sell" to 1, "exit" to 2)
        val idx = top[rest[0]] ?: throw IllegalArgumentException("unknown shop item: ${rest[0]}")
        if (idx > 0) out += MenuTap("DOWN", idx)
        out += MenuTap("A")
        if (rest.size >= 2) {
            val item = rest[1].toInt()
            require(item in 0..7) { "shop item must be 0..7" }
            if (item > 0) out += MenuTap("DOWN", item)
            out += MenuTap("A")
        }
        if (rest.size >= 3 && rest[2].startsWith("char")) {
            val n = rest[2].removePrefix("char").toInt()
            require(n in 1..4) { "char must be 1..4" }
            if (n > 1) out += MenuTap("DOWN", n - 1)
            out += MenuTap("A")
        }
        return out
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/tools/MenuWalker.kt
git commit -m "feat(v2): MenuWalker — FF1 menu path parser"
```

---

### Task B2: MenuWalker test (TEST 3 of 3)

**Files:**
- Create: `knes-agent/src/test/kotlin/knes/agent/v2/tools/MenuWalkerTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package knes.agent.v2.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly

class MenuWalkerTest : StringSpec({
    val w = MenuWalker()

    "main/equip/char1/weapon/0 emits expected sequence" {
        w.parse("main/equip/char1/weapon/0") shouldContainExactly listOf(
            MenuTap("B"),
            MenuTap("DOWN", 2),   // equip is 3rd item (idx 2)
            MenuTap("A"),
            MenuTap("A"),          // char1 — no DOWN needed
            MenuTap("A"),          // weapon — first slot, no DOWN
        )
    }

    "main/equip/char3/weapon/1 navigates to char3 and slot 1" {
        val seq = w.parse("main/equip/char3/weapon/1")
        seq[3] shouldBe MenuTap("DOWN", 2)    // char3 → DOWN×2 then A
        seq[4] shouldBe MenuTap("A")
        seq[5] shouldBe MenuTap("DOWN", 1)    // slot 1 → DOWN×1 then A
    }

    "shop/buy/0/char1 emits buy+item0+char1" {
        w.parse("shop/buy/0/char1") shouldContainExactly listOf(
            MenuTap("A"),          // buy (idx 0)
            MenuTap("A"),          // item 0
            MenuTap("A"),          // char1
        )
    }

    "invalid root throws" {
        runCatching { w.parse("nonsense/x") }.isFailure shouldBe true
    }

    "char out of range throws" {
        runCatching { w.parse("main/equip/char9/weapon/0") }.isFailure shouldBe true
    }
})
```

- [ ] **Step 2: Run and verify**

Run: `./gradlew :knes-agent:test --tests 'knes.agent.v2.tools.MenuWalkerTest' --quiet`
Expected: green. If red, fix MenuWalker or test expectations — common: off-by-one on `DOWN` count.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/test/kotlin/knes/agent/v2/tools/MenuWalkerTest.kt
git commit -m "test(v2): MenuWalker path parsing"
```

---

### Task B3: ToolSurface dispatcher

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt`

- [ ] **Step 1: Define interface + implementation**

```kotlin
package knes.agent.v2.tools

import knes.agent.skills.BuyAtShop
import knes.agent.skills.EquipWeapon
import knes.agent.skills.RestAtInn
import knes.agent.skills.SkillResult
import knes.agent.skills.WalkOverworldTo
import knes.agent.skills.ExitInterior
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.runtime.Phase

sealed class ToolOutcome {
    data class Ok(val message: String = "", val data: Map<String, String> = emptyMap()) : ToolOutcome()
    data class Fail(val message: String) : ToolOutcome()
    data class Reject(val reason: String) : ToolOutcome()
}

interface ToolSurface {
    suspend fun walkTo(x: Int, y: Int): ToolOutcome
    suspend fun interactAt(x: Int, y: Int): ToolOutcome
    suspend fun useMenu(path: String): ToolOutcome
    suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome
    suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome
    suspend fun restAtInn(innMapId: String): ToolOutcome
    suspend fun battleFightAll(): ToolOutcome
}

class DefaultToolSurface(
    private val toolset: EmulatorToolset,
    private val phaseProvider: () -> Phase,
    // injected v1 skill instances (constructed in Main wiring)
    private val walkOverworld: WalkOverworldTo,
    private val exitInterior: ExitInterior,
    private val menuWalker: MenuWalker = MenuWalker(),
) : ToolSurface {

    override suspend fun walkTo(x: Int, y: Int): ToolOutcome = when (phaseProvider()) {
        Phase.Overworld -> wrap(walkOverworld.invoke(mapOf("targetX" to "$x", "targetY" to "$y", "maxSteps" to "32")))
        Phase.Indoors   -> wrap(exitInterior.invoke(mapOf("maxSteps" to "64")))
        else -> ToolOutcome.Reject("walkTo not applicable in phase ${phaseProvider()}")
    }

    override suspend fun interactAt(x: Int, y: Int): ToolOutcome {
        val walk = walkTo(x, y)
        if (walk !is ToolOutcome.Ok) return walk
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
        return ToolOutcome.Ok("interactAt done")
    }

    override suspend fun useMenu(path: String): ToolOutcome {
        val taps = try { menuWalker.parse(path) }
                   catch (e: IllegalArgumentException) { return ToolOutcome.Reject(e.message ?: "bad path") }
        for (t in taps) toolset.tap(button = t.button, count = t.count, pressFrames = 5, gapFrames = 12)
        return ToolOutcome.Ok("menu walked: $path")
    }

    override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome {
        // Delegate to v1 BuyAtShop V5.45 via invokeWithAdvisor entry point.
        // Concrete signature wiring done in Main; here we use a thin lambda.
        return ToolOutcome.Reject("buyAtShop wiring TBD until Main wires v1 BuyAtShop instance")
            .also { System.err.println("[v2.tool] buyAtShop placeholder hit — wire in Phase C.") }
    }

    override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome {
        // v1 EquipWeapon known-buggy (MenuStuck). Reuse anyway per spec section 13.
        return ToolOutcome.Reject("equipWeapon wiring TBD until Main wires v1 instance")
    }

    override suspend fun restAtInn(innMapId: String): ToolOutcome =
        ToolOutcome.Reject("restAtInn wiring TBD")

    override suspend fun battleFightAll(): ToolOutcome {
        val r = toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
        return if (r.ok) ToolOutcome.Ok(r.message, r.state) else ToolOutcome.Fail(r.message)
    }

    private fun wrap(s: SkillResult): ToolOutcome =
        if (s.ok) ToolOutcome.Ok(s.message, s.ramAfter.mapValues { it.value.toString() })
        else ToolOutcome.Fail(s.message)
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors. (Macro wiring placeholders are intentional — completed in Task C0.)

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt
git commit -m "feat(v2): ToolSurface dispatcher (walkTo/interactAt/useMenu + macro stubs)"
```

---

### Task B4: Wire BuyAtShop / EquipWeapon / RestAtInn macros into ToolSurface

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt`

- [ ] **Step 1: Add constructor params + replace placeholders**

Add to `DefaultToolSurface` constructor:

```kotlin
class DefaultToolSurface(
    // ... existing params ...
    private val buyAtShopSkill: BuyAtShop,
    private val equipWeaponSkill: EquipWeapon,
    private val restAtInnSkill: RestAtInn,
) : ToolSurface {
```

Replace the three placeholder bodies:

```kotlin
override suspend fun buyAtShop(items: List<Int>, charSlots: List<Int>): ToolOutcome {
    require(items.size == charSlots.size) { "items.size must equal charSlots.size" }
    // v1 BuyAtShop V5.45 takes a single (itemSlot, forCharSlot) pair via invoke,
    // OR a batch via invokeWithAdvisor. Iterate the simple way; macros own their
    // own per-iter PNG dump and shop-dialog state.
    val results = mutableListOf<String>()
    for ((i, c) in items.zip(charSlots)) {
        val r = buyAtShopSkill.invoke(mapOf(
            "itemSlot" to "$i", "forCharSlot" to "$c", "expectedKeeperKind" to "weapon",
        ))
        results += "i=$i c=$c → ${r.message}"
        if (!r.ok) return ToolOutcome.Fail("buyAtShop aborted at i=$i c=$c: ${r.message}")
    }
    return ToolOutcome.Ok(results.joinToString("; "))
}

override suspend fun equipWeapon(charSlot: Int, weaponSlot: Int): ToolOutcome {
    val r = equipWeaponSkill.invoke(mapOf("charSlot" to "$charSlot", "weaponSlot" to "$weaponSlot"))
    return if (r.ok) ToolOutcome.Ok(r.message) else ToolOutcome.Fail(r.message)
}

override suspend fun restAtInn(innMapId: String): ToolOutcome {
    val r = restAtInnSkill.invoke(mapOf("innInteriorMapId" to innMapId))
    return if (r.ok) ToolOutcome.Ok(r.message) else ToolOutcome.Fail(r.message)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors. If v1 `BuyAtShop`/`EquipWeapon`/`RestAtInn` constructor signatures don't match assumptions, inspect them and adjust args map keys.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt
git commit -m "feat(v2): wire BuyAtShop/EquipWeapon/RestAtInn macros into ToolSurface"
```

---

## Phase C — Agents

### Task C1: GeminiPro31Client + SonnetClient + HaikuClient wrappers

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/llm/GeminiPro31Client.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/llm/SonnetClient.kt`
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/llm/HaikuClient.kt`

- [ ] **Step 1: GeminiPro31Client (thin wrapper, reuse v1 GeminiVisionConsult pattern)**

```kotlin
package knes.agent.v2.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal Gemini 3.1 Pro client for v2 agents. Sends a text+image prompt,
 * returns model text. Reuses HTTP wiring style from v1 GeminiVisionConsult.
 */
class GeminiPro31Client(private val apiKey: String) : AutoCloseable {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val model = "gemini-3.1-pro"

    suspend fun generate(prompt: String, imageB64: String? = null): String {
        val parts = buildList<JsonObject> {
            add(JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(prompt))))
            if (imageB64 != null) {
                add(JsonObject(mapOf("inline_data" to JsonObject(mapOf(
                    "mime_type" to kotlinx.serialization.json.JsonPrimitive("image/png"),
                    "data" to kotlinx.serialization.json.JsonPrimitive(imageB64),
                )))))
            }
        }
        val body = buildString {
            append("""{"contents":[{"parts":""")
            append(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(JsonObject.serializer()), parts))
            append("}]}")
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val resp = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        val candidates = json.parseToJsonElement(resp).jsonObject["candidates"]?.jsonArray
        return candidates?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw RuntimeException("Gemini response unparseable: ${resp.take(500)}")
    }

    override fun close() { http.close() }
}
```

- [ ] **Step 2: SonnetClient — reuse v1 AnthropicSession**

```kotlin
package knes.agent.v2.llm

import knes.agent.llm.AnthropicSession

class SonnetClient(private val anthropic: AnthropicSession) {
    val modelId = "claude-sonnet-4-6"
    /**
     * Thin call site. The real agent loop in ExecutorAgent will use Koog's
     * tool calling on this model. For simple ask/answer fall through to
     * anthropic.chat() (whatever the v1 API exposes) — wired in Task C3.
     */
}
```

- [ ] **Step 3: HaikuClient — same pattern**

```kotlin
package knes.agent.v2.llm

import knes.agent.llm.AnthropicSession

class HaikuClient(private val anthropic: AnthropicSession) {
    val modelId = "claude-haiku-4-5-20251001"
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/llm/
git commit -m "feat(v2): LLM client wrappers (Gemini 3.1 Pro + Sonnet 4.6 + Haiku 4.5)"
```

---

### Task C2: AdvisorAgent (Gemini 3.1 Pro planner)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/agents/AdvisorAgent.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.agents

import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.Plan
import knes.agent.v2.runtime.PlanEntry
import knes.agent.v2.runtime.PlanStep
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory

/**
 * Advisor writes a numbered plan into current_plan.json and appends a
 * PlanEntry to campaign.json. Called on T=0, stuck-signal, and phase
 * boundaries. ~2% of total LLM calls.
 */
class AdvisorAgent(
    private val gemini: GeminiPro31Client,
    private val memory: V2Memory,
    private val run: V2RunDirectory,
) {
    suspend fun plan(reason: String, screenshotB64: String, turn: Int) {
        val prompt = buildPrompt(reason)
        val raw = gemini.generate(prompt, imageB64 = screenshotB64)
        val plan = parsePlan(raw, milestone = currentMilestone(), turn = turn)
        memory.setPlan(plan)
        memory.campaign.plans += PlanEntry(
            turn = turn, by = "advisor",
            summary = plan.steps.firstOrNull()?.description ?: "(no steps)",
            snapshot = "snapshots/turn-%05d.png".format(turn),
            reason = reason,
        )
        memory.saveCampaign()
        System.err.println("[v2.advisor] turn=$turn reason=$reason steps=${plan.steps.size}")
    }

    private fun currentMilestone(): String =
        memory.campaign.milestones.firstOrNull { it.status == "in_progress" }?.id
            ?: memory.campaign.milestones.firstOrNull { it.status == "pending" }?.id
            ?: "boot"

    private fun buildPrompt(reason: String): String = """
        You are the FF1 strategic advisor. Produce a NUMBERED plan (max 8 steps)
        to advance the current milestone. Respond ONLY with JSON.

        Current milestone: ${currentMilestone()}
        Trigger reason: $reason
        Campaign so far: ${memory.campaign.milestones.joinToString { "${it.id}=${it.status}" }}
        Recent plans: ${memory.campaign.plans.takeLast(3).joinToString("\n") { "T${it.turn}: ${it.summary}" }}

        Output schema:
        {"steps":[
          {"index":0,"description":"...","intentTool":"walkTo|interactAt|useMenu|buyAtShop|equipWeapon|restAtInn|battleFightAll","intentArgs":{"x":"15","y":"22"}},
          ...
        ]}

        Available tools: walkTo(x,y), interactAt(x,y), useMenu(path), buyAtShop(items,charSlots),
        equipWeapon(charSlot,weaponSlot), restAtInn(innMapId), battleFightAll().
    """.trimIndent()

    private fun parsePlan(raw: String, milestone: String, turn: Int): Plan {
        val jsonText = raw.substringAfter("{").let { "{$it" }.substringBeforeLast("}") + "}"
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(PlanWire.serializer(), jsonText)
        return Plan(
            createdAtTurn = turn,
            milestone = milestone,
            steps = parsed.steps.mapIndexed { i, s ->
                PlanStep(i, s.description, s.intentTool, s.intentArgs)
            },
        )
    }

    @kotlinx.serialization.Serializable
    private data class PlanWire(val steps: List<StepWire>)
    @kotlinx.serialization.Serializable
    private data class StepWire(
        val description: String,
        val intentTool: String? = null,
        val intentArgs: Map<String, String>? = null,
    )
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/agents/AdvisorAgent.kt
git commit -m "feat(v2): AdvisorAgent — Gemini 3.1 Pro plan writer"
```

---

### Task C3: ExecutorAgent (Sonnet 4.6 per-turn picker)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/agents/ExecutorAgent.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.agents

import knes.agent.llm.AnthropicSession
import knes.agent.v2.llm.SonnetClient
import knes.agent.v2.runtime.Plan
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.tools.ToolOutcome
import knes.agent.v2.tools.ToolSurface

data class ExecutorDecision(
    val tool: String,
    val args: Map<String, String>,
    val reasoning: String,
    val outcome: ToolOutcome,
    val ms: Long,
)

/**
 * Per-turn tool picker. Given current plan + observation (screenshot + RAM
 * digest), Sonnet 4.6 picks the next tool and arguments. ToolSurface invokes
 * it; we return the outcome for memory.appendTurn.
 *
 * Implementation: for the first cut, prefer the plan's intentTool/intentArgs
 * verbatim (i.e. trust Advisor). If plan cursor exhausted OR intentTool null
 * OR last 2 outcomes were Fail/Reject, ask Sonnet via a structured prompt.
 */
class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val sonnet: SonnetClient,
    private val tools: ToolSurface,
    private val memory: V2Memory,
) {
    private val recentOutcomes = ArrayDeque<String>(4)

    suspend fun act(screenshotB64: String, ramDigest: String): ExecutorDecision {
        val started = System.currentTimeMillis()
        val plan = memory.currentPlan
        val step = plan?.steps?.getOrNull(plan.cursor)

        val (tool, args, reasoning) =
            if (shouldUsePlanHint(step)) {
                Triple(step!!.intentTool!!, step.intentArgs ?: emptyMap(), "plan step ${step.index}: ${step.description}")
            } else {
                askLlm(plan, screenshotB64, ramDigest)
            }

        val outcome = dispatch(tool, args)
        recentOutcomes.addLast(outcome.javaClass.simpleName); if (recentOutcomes.size > 4) recentOutcomes.removeFirst()
        if (outcome is ToolOutcome.Ok && plan != null) advancePlan(plan)

        return ExecutorDecision(tool, args, reasoning, outcome, System.currentTimeMillis() - started)
    }

    private fun shouldUsePlanHint(step: knes.agent.v2.runtime.PlanStep?): Boolean {
        if (step == null || step.intentTool == null) return false
        val recentFails = recentOutcomes.takeLast(2).count { it != "Ok" }
        return recentFails < 2
    }

    private suspend fun askLlm(plan: Plan?, screenshotB64: String, ramDigest: String): Triple<String, Map<String, String>, String> {
        // For first cut, ask anthropic.chat with a structured prompt — wire to the
        // same channel v1 ExecutorAgent uses. If v1 API is too coupled, fall back
        // to picking from plan tail or to no-op `useMenu("main/exit")`.
        // TODO(C3-followup): plug a Koog tool registry so Sonnet can call tools natively.
        val tail = plan?.steps?.lastOrNull()
        if (tail?.intentTool != null) return Triple(tail.intentTool, tail.intentArgs ?: emptyMap(), "fallback to plan tail")
        return Triple("useMenu", mapOf("path" to "main/exit"), "no plan + no LLM wired yet — safe no-op")
    }

    private suspend fun dispatch(tool: String, args: Map<String, String>): ToolOutcome = when (tool) {
        "walkTo"          -> tools.walkTo(args.getValue("x").toInt(), args.getValue("y").toInt())
        "interactAt"      -> tools.interactAt(args.getValue("x").toInt(), args.getValue("y").toInt())
        "useMenu"         -> tools.useMenu(args.getValue("path"))
        "buyAtShop"       -> tools.buyAtShop(
            args.getValue("items").split(",").map { it.toInt() },
            args.getValue("charSlots").split(",").map { it.toInt() },
        )
        "equipWeapon"     -> tools.equipWeapon(args.getValue("charSlot").toInt(), args.getValue("weaponSlot").toInt())
        "restAtInn"       -> tools.restAtInn(args.getValue("innMapId"))
        "battleFightAll"  -> tools.battleFightAll()
        else              -> ToolOutcome.Reject("unknown tool: $tool")
    }

    private fun advancePlan(plan: Plan) {
        val advanced = plan.copy(cursor = (plan.cursor + 1).coerceAtMost(plan.steps.size))
        memory.setPlan(advanced)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/agents/ExecutorAgent.kt
git commit -m "feat(v2): ExecutorAgent — plan-hint first, LLM fallback (placeholder)"
```

---

### Task C4: ReviewerAgent (Haiku 4.5 audit)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/agents/ReviewerAgent.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.agents

import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.runtime.ReviewEntry
import knes.agent.v2.runtime.V2Memory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

class ReviewerAgent(
    private val haiku: HaikuClient,
    private val memory: V2Memory,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Read entire Memory (Campaign + on-disk landmarks/warps/blockages),
     * ask Haiku for audit issues, parse JSON, apply REMOVE actions, append
     * FLAG actions to cartographer-flags.json.
     *
     * Safety:
     *   - Every action appended to review.jsonl BEFORE mutation.
     *   - Only `remove_stale` mutates Memory files.
     *   - `flag_for_cartographer` only writes to cartographer-flags.json.
     */
    suspend fun audit(turn: Int) {
        val memoryDigest = digestMemory()
        val prompt = """
            You are the FF1 Memory auditor. Identify issues with the following game memory.
            Return ONLY JSON: {"actions":[{"kind":"remove_stale|flag_for_cartographer","entry":"...","reason":"..."}]}

            Memory digest:
            $memoryDigest

            Rules:
            - remove_stale: entry hasn't been referenced in plans/recent decisions OR is older than 50 turns OR contradicts ground truth
            - flag_for_cartographer: inconsistency between two ground-truth artifacts (e.g. blockage at a tile that terrain says walkable)
            - Never invent entries. Only reference what's in the digest.
            - Empty actions list is fine if Memory is clean.
        """.trimIndent()

        // For first cut, use a simple chat invocation through v1 AnthropicSession
        // (HaikuClient currently only holds the modelId; full integration in followup task)
        val response = invokeHaiku(prompt)
        val parsed = json.decodeFromString(AuditWire.serializer(), extractJson(response))

        val removed = mutableListOf<String>()
        val flagged = mutableListOf<String>()
        for (a in parsed.actions) {
            val line = json.encodeToString(AuditAction.serializer(),
                AuditAction(turn = turn, kind = a.kind, entry = a.entry, reason = a.reason))
            memory.appendReviewLine(line)
            when (a.kind) {
                "remove_stale" -> { applyRemove(a.entry); removed += a.entry }
                "flag_for_cartographer" -> { appendCartographerFlag(a.entry, a.reason); flagged += a.entry }
                else -> System.err.println("[v2.reviewer] unknown action kind: ${a.kind}")
            }
        }

        memory.campaign.reviews += ReviewEntry(turn = turn, removed = removed, flagged = flagged)
        memory.saveCampaign()
        System.err.println("[v2.reviewer] turn=$turn removed=${removed.size} flagged=${flagged.size}")
    }

    private fun digestMemory(): String {
        val campaignJson = Files.readString(memory.run.campaignJson)
        val landmarks = if (memory.run.landmarksJson.toFile().exists()) Files.readString(memory.run.landmarksJson) else "{}"
        val warps = if (memory.run.warpsJson.toFile().exists()) Files.readString(memory.run.warpsJson) else "{}"
        val blockages = if (memory.run.blockagesJson.toFile().exists()) Files.readString(memory.run.blockagesJson) else "{}"
        return "campaign:$campaignJson\nlandmarks:$landmarks\nwarps:$warps\nblockages:$blockages"
    }

    private fun applyRemove(entry: String) {
        // entry format: "landmarks:KEY" | "warps:KEY" | "blockages:(x,y)"
        // For first cut just log — full mutation wired in followup once landmark
        // JSON schemas are stable.
        System.err.println("[v2.reviewer] would remove: $entry")
    }

    private fun appendCartographerFlag(entry: String, reason: String) {
        val path = memory.run.cartographerFlagsJson
        val existing = if (path.toFile().exists()) Files.readString(path) else "[]"
        val list = json.parseToJsonElement(existing).toString().trimEnd(']') +
                   (if (existing.trim() == "[]") "" else ",") +
                   """{"entry":"$entry","reason":"$reason"}]"""
        Files.writeString(path, list)
    }

    private suspend fun invokeHaiku(prompt: String): String {
        // Placeholder — Wire AnthropicSession.send with model=haiku in Task D2.
        return """{"actions":[]}"""
    }

    private fun extractJson(raw: String): String =
        raw.substringAfter("{").let { "{$it" }.substringBeforeLast("}") + "}"

    @Serializable
    private data class AuditWire(val actions: List<AuditAction>)
    @Serializable
    private data class AuditAction(
        val turn: Int = 0,
        val kind: String,
        val entry: String,
        val reason: String,
    )
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/agents/ReviewerAgent.kt
git commit -m "feat(v2): ReviewerAgent — Haiku audit (Memory mutation safety scaffolding)"
```

---

### Task C5: CartographerAgent (Gemini 3.1 Pro vision exploration)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/agents/CartographerAgent.kt`

- [ ] **Step 1: Implement**

```kotlin
package knes.agent.v2.agents

import knes.agent.perception.FogOfWar
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.SnapshotDumper
import knes.agent.v2.runtime.V2Memory

/**
 * Online vision exploration phase. Pushes party around spawn using vision-LLM
 * cardinal hints to fill fog and identify landmarks (innkeeper / weapon shop /
 * armor shop in Coneria).
 *
 * Budget: time + vision-call cap from V2Config. If exceeded, falls back to
 * static ROM-decoder map only.
 *
 * IMPORTANT: vision prompts MUST enforce locate-party → locate-target →
 * derive-direction (per repo's feedback_locate_party_first.md).
 */
class CartographerAgent(
    private val gemini: GeminiPro31Client,
    private val toolset: EmulatorToolset,
    private val memory: V2Memory,
    private val snapshotDumper: SnapshotDumper,
    private val overworldMap: OverworldMap,
    private val fog: FogOfWar,
    private val landmarks: LandmarkMemory,
    private val budgetSeconds: Int,
    private val maxVisionCalls: Int,
) {
    suspend fun exploreInitialOverworld() {
        val started = System.currentTimeMillis()
        var visionCalls = 0
        var turnCounter = 0   // Cartographer turns (not yet entered campaign loop)

        System.err.println("[v2.cartographer] start: budget=${budgetSeconds}s maxCalls=$maxVisionCalls")

        while (true) {
            val elapsed = (System.currentTimeMillis() - started) / 1000
            if (elapsed > budgetSeconds) { System.err.println("[v2.cartographer] time budget exceeded ($elapsed s)"); break }
            if (visionCalls >= maxVisionCalls) { System.err.println("[v2.cartographer] vision call cap reached"); break }

            val ram = toolset.getState().ram
            val worldX = ram["worldX"] ?: 0
            val worldY = ram["worldY"] ?: 0

            // Fog merge with current viewport (cheap, no LLM).
            val viewport = overworldMap.readFullMapView(worldX to worldY)
            fog.merge(viewport)

            // Stop condition: all reachable adjacent tiles known.
            if (fog.allReachableKnown(worldX to worldY)) {
                System.err.println("[v2.cartographer] frontier exhausted at ($worldX,$worldY)")
                break
            }

            // Ask Gemini for next direction. Enforce locate-party-first contract.
            val snap = toolset.getScreen().base64
            val direction = askGeminiNextDirection(snap, worldX, worldY)
            visionCalls++
            when (direction.uppercase()) {
                "N" -> toolset.tap("UP", 1)
                "S" -> toolset.tap("DOWN", 1)
                "E" -> toolset.tap("RIGHT", 1)
                "W" -> toolset.tap("LEFT", 1)
                "DONE" -> break
                else -> System.err.println("[v2.cartographer] unexpected dir: $direction")
            }

            turnCounter++
            snapshotDumper.dump(-turnCounter)  // negative turns reserved for pre-campaign Cartographer iters
        }

        // Landmark scans: weapon/armor/inn discovery in Coneria.
        // For first cut, defer to v1 DiscoverShop/DiscoverInn skills wired in Main.
        // This method just builds overworld fog. Indoor scans happen lazily during
        // first Executor visit to each building.

        System.err.println("[v2.cartographer] done: ${visionCalls} vision calls, ${turnCounter} steps")
    }

    suspend fun targetedRepass(flags: List<String>) {
        // Triggered by Reviewer when ≥3 inconsistencies flagged. For each flag,
        // walk to the flagged tile and re-classify with vision.
        System.err.println("[v2.cartographer] targetedRepass: ${flags.size} flags (stub)")
    }

    private suspend fun askGeminiNextDirection(snap: String, x: Int, y: Int): String {
        val prompt = """
            You are looking at the FF1 NES overworld viewport.
            Step 1: LOCATE the party sprite on the screen — DO NOT use prior knowledge of FF1 geography.
            Step 2: From that visual position, identify which cardinal direction (N/S/E/W) leads to the most UNEXPLORED area.
            Step 3: Return that direction.

            Party world-coords: ($x, $y).
            Return ONLY the letter: N or S or E or W, or DONE if no unexplored frontier.
        """.trimIndent()
        return gemini.generate(prompt, imageB64 = snap).trim().take(4)
    }
}
```

- [ ] **Step 2: Verify `FogOfWar.allReachableKnown` exists or stub it**

Run: `grep -n 'allReachableKnown' knes-agent/src/main/kotlin/knes/agent/perception/FogOfWar.kt`

If absent: open `FogOfWar.kt` and add the method (a small extension is fine — for first cut, return false to force vision exploration until budget exhausted):

```kotlin
fun allReachableKnown(from: Pair<Int, Int>): Boolean = false  // TODO refine via fog-coverage check
```

Commit that change separately so the modification stays minimal:

```bash
git add knes-agent/src/main/kotlin/knes/agent/perception/FogOfWar.kt
git commit -m "feat(v2): FogOfWar.allReachableKnown stub for Cartographer frontier"
```

- [ ] **Step 3: Compile + commit Cartographer**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/agents/CartographerAgent.kt
git commit -m "feat(v2): CartographerAgent — Gemini 3.1 Pro online vision exploration"
```

---

## Phase D — Orchestration (wire it together)

### Task D1: V2Main bootstrap (ROM load + run dir + agents constructed, no loop yet)

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt`

- [ ] **Step 1: Replace Main with bootstrap**

```kotlin
package knes.agent.v2

import knes.agent.llm.AnthropicSession
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.skills.BuyAtShop
import knes.agent.skills.EquipWeapon
import knes.agent.skills.ExitInterior
import knes.agent.skills.RestAtInn
import knes.agent.skills.WalkOverworldTo
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.agents.AdvisorAgent
import knes.agent.v2.agents.CartographerAgent
import knes.agent.v2.agents.ExecutorAgent
import knes.agent.v2.agents.ReviewerAgent
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.llm.SonnetClient
import knes.agent.v2.runtime.SnapshotDumper
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory
import knes.agent.v2.runtime.Watchdog
import knes.agent.v2.tools.DefaultToolSurface
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("ANTHROPIC_API_KEY not set")
    val geminiKey = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("GEMINI_API_KEY not set (Gemini 3.1 Pro required for v2)")

    val run = if (cfg.resumeDir != null) V2RunDirectory.resume(cfg.resumeDir)
             else V2RunDirectory.freshRun()
    System.err.println("[v2.main] run dir: ${run.root}")

    runBlocking {
        AnthropicSession(anthropicKey).use { anthropic ->
            GeminiPro31Client(geminiKey).use { gemini ->
                val session = EmulatorSession()
                val toolset = EmulatorToolset(session)
                require(toolset.loadRom(cfg.rom).ok) { "Failed to load ROM: ${cfg.rom}" }
                require(toolset.applyProfile(cfg.profile).ok) { "Failed to apply profile: ${cfg.profile}" }

                // Perception (shared with v1)
                val overworldMap = OverworldMap.fromRom(File(cfg.rom))
                val fog = FogOfWar()
                val mapSession = MapSession(InteriorMapLoader(File(cfg.rom).readBytes()), fog)
                val landmarks = LandmarkMemory()

                // v2 runtime
                val memory = V2Memory(run)
                val snapshotDumper = SnapshotDumper(toolset, run)
                val watchdog = Watchdog()

                // Wire macros (v1 skills) — wired as plain instances, see v1 Main for full constructor args
                val walkOverworld = WalkOverworldTo(toolset, overworldMap, fog, knes.agent.pathfinding.ViewportPathfinder(), knes.agent.runtime.ToolCallLog())
                val exitInterior = ExitInterior(toolset, mapSession, fog,
                    knes.agent.pathfinding.InteriorPathfinder(knes.agent.perception.InteriorMemory()) { toolset.getState().ram["currentMapId"] ?: -1 },
                    knes.agent.runtime.ToolCallLog(), knes.agent.perception.InteriorMemory())
                // BuyAtShop / EquipWeapon / RestAtInn full constructor args mirror v1 Main wiring — for first
                // cut leave as null guards; ToolSurface placeholder paths still return Reject. Promote to
                // real wiring in D3 after Smoke 0 confirms baseline.
                val tools = DefaultToolSurface(
                    toolset = toolset,
                    phaseProvider = { knes.agent.v2.runtime.Phase.fromRam(toolset.getState().ram) },
                    walkOverworld = walkOverworld,
                    exitInterior = exitInterior,
                    buyAtShopSkill = TODO("wire in D3"),
                    equipWeaponSkill = TODO("wire in D3"),
                    restAtInnSkill = TODO("wire in D3"),
                )

                // Agents
                val advisor = AdvisorAgent(gemini, memory, run)
                val executor = ExecutorAgent(anthropic, SonnetClient(anthropic), tools, memory)
                val reviewer = ReviewerAgent(HaikuClient(anthropic), memory)
                val cartographer = CartographerAgent(
                    gemini, toolset, memory, snapshotDumper, overworldMap, fog, landmarks,
                    cfg.cartographerBudgetSeconds, cfg.cartographerMaxVisionCalls,
                )

                System.err.println("[v2.main] bootstrap complete — campaign loop in D2")
            }
        }
    }
}
```

NOTE: this compiles but DOES NOT RUN — `TODO("wire in D3")` will crash at first ToolSurface construction. Step 2 verifies that compilation succeeds, leaving the runtime fix for Task D3.

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors (warnings about TODO are fine).

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/Main.kt
git commit -m "feat(v2): bootstrap — load ROM, construct agents (no loop yet)"
```

---

### Task D2: Resume protocol (savestate load + decision replay)

**Files:**
- Create: `knes-agent/src/main/kotlin/knes/agent/v2/runtime/Resumer.kt`
- Modify: `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt`

- [ ] **Step 1: Implement Resumer**

```kotlin
package knes.agent.v2.runtime

import knes.api.EmulatorSession
import java.nio.file.Files

class Resumer(
    private val session: EmulatorSession,
    private val run: V2RunDirectory,
    private val memory: V2Memory,
) {
    fun resume() {
        val lastTurn = memory.campaign.lastTurn
        if (lastTurn == 0) {
            System.err.println("[v2.resume] lastTurn=0 — nothing to resume; running fresh")
            return
        }
        val checkpointTurn = (lastTurn / 100) * 100
        val checkpoint = run.savestate(checkpointTurn)
        if (!checkpoint.toFile().exists()) {
            System.err.println("[v2.resume] WARN: no checkpoint at T$checkpointTurn — starting from emulator boot. " +
                "Decision replay from T1 not implemented; advisor will plan from observed RAM.")
            return
        }
        // PPU pre-warm (per v1 Main.kt:81-83)
        session.advanceFrames(120)
        val ok = session.loadState(Files.readAllBytes(checkpoint))
        require(ok) { "loadState failed for $checkpoint" }
        session.advanceFrames(120)
        System.err.println("[v2.resume] restored T$checkpointTurn (last_turn=$lastTurn). " +
            "Replay from T$checkpointTurn to T$lastTurn not implemented yet — emulator at checkpoint, " +
            "memory at last_turn — Advisor will reconcile via campaign.json digest.")
        // TODO(D2-followup): replay button events from decisions/turn-(checkpointTurn+1).json..turn-lastTurn.json
    }
}
```

- [ ] **Step 2: Wire into Main**

In `Main.kt` after `val memory = V2Memory(run)`, add:

```kotlin
if (cfg.resumeDir != null) Resumer(session, run, memory).resume()
```

- [ ] **Step 3: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/
git commit -m "feat(v2): Resumer — savestate restore (decision replay TODO)"
```

---

### Task D3: Wire BuyAtShop / EquipWeapon / RestAtInn full constructor args

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt`

- [ ] **Step 1: Inspect v1 Main to copy macro wiring**

Read `knes-agent/src/main/kotlin/knes/agent/Main.kt:108-176` and copy the relevant constructor calls for `BuyAtShop`, `EquipWeapon`, `RestAtInn`. Replace the three `TODO("wire in D3")` lines.

For instance (illustrative, actual args resolved by reading v1):

```kotlin
val toolCallLog = knes.agent.runtime.ToolCallLog()
val visionInteriorNavigator = knes.agent.perception.AnthropicVisionInteriorNavigator(apiKey = anthropicKey)
val buyAtShop = BuyAtShop(toolset, landmarks, /* vision = */ knes.agent.explorer.GeminiVisionConsult(apiKey = geminiKey), toolCallLog)
val equipWeapon = EquipWeapon(toolset)
val restAtInn = RestAtInn(toolset)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors. Fix constructor signature mismatches by reading the v1 skill class headers.

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/Main.kt
git commit -m "feat(v2): wire macro skills with full v1 constructor args"
```

---

### Task D4: Campaign loop

**Files:**
- Modify: `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt`

- [ ] **Step 1: Add the loop**

After the bootstrap, replace `System.err.println("[v2.main] bootstrap complete …")` with the loop:

```kotlin
// Phase 0: bootstrap
val firstTurn = memory.campaign.lastTurn + 1
if (cfg.fresh) {
    cartographer.exploreInitialOverworld()
    snapshotDumper.dump(0)
    val snap0 = toolset.getScreen().base64
    advisor.plan(reason = "T0 fresh campaign", screenshotB64 = snap0, turn = firstTurn)
} else {
    val snap = toolset.getScreen().base64
    advisor.plan(reason = "resume context", screenshotB64 = snap, turn = firstTurn)
}

// Phase 1: campaign loop
val recentExecutorOutcomes = ArrayDeque<String>(4)
var turn = firstTurn
while (turn <= cfg.maxTurns && !memory.campaign.done) {
    val snap = run {
        val b64 = toolset.getScreen().base64
        snapshotDumper.dump(turn)
        b64
    }
    val state = toolset.getState()
    val phase = knes.agent.v2.runtime.Phase.fromRam(state.ram)
    val ramDigest = state.ram.entries.joinToString(",") { "${it.key}=${it.value}" }

    val decision = executor.act(screenshotB64 = snap, ramDigest = ramDigest)

    val outcomeLabel = decision.outcome.javaClass.simpleName
    recentExecutorOutcomes.addLast(outcomeLabel)
    if (recentExecutorOutcomes.size > 4) recentExecutorOutcomes.removeFirst()

    val ramHash = state.ram.values.fold(0) { acc, v -> 31 * acc + v }
    val skillProgress = decision.outcome is knes.agent.v2.tools.ToolOutcome.Ok
    watchdog.observe(phase, ramHash, skillProgress)

    memory.appendTurn(
        knes.agent.v2.runtime.TurnLog(
            turn = turn, frame = state.frame, phase = phase.name,
            ram = state.ram,
            snapshot = "snapshots/turn-%05d.png".format(turn),
            executor = knes.agent.v2.runtime.ExecutorTrace(
                model = SonnetClient(anthropic).modelId,
                tool = decision.tool, args = decision.args,
                reasoningSummary = decision.reasoning,
                outcome = outcomeLabel.lowercase(),
                message = (decision.outcome as? knes.agent.v2.tools.ToolOutcome.Ok)?.message
                    ?: (decision.outcome as? knes.agent.v2.tools.ToolOutcome.Fail)?.message
                    ?: (decision.outcome as? knes.agent.v2.tools.ToolOutcome.Reject)?.reason,
                ms = decision.ms,
            ),
            watchdog = knes.agent.v2.runtime.WatchdogTrace(
                stuckCounter = watchdog.counter(), threshold = watchdog.threshold(phase),
            ),
        )
    )

    if (watchdog.stuckSignal(phase)) {
        System.err.println("[v2.main] stuck-signal — ${watchdog.diagnose(phase, recentExecutorOutcomes.toList())}")
        advisor.plan(reason = "stuck: ${watchdog.diagnose(phase, recentExecutorOutcomes.toList())}",
                     screenshotB64 = snap, turn = turn)
        watchdog.reset()
    }

    if (turn % 50 == 0) {
        reviewer.audit(turn)
        // cartographer.targetedRepass triggered via memory.cartographerFlags when ≥3
    }

    if (turn % 100 == 0) {
        val saveBytes = session.saveState()
        java.nio.file.Files.write(run.savestate(turn), saveBytes)
        System.err.println("[v2.main] checkpoint saved at T$turn (${saveBytes.size} bytes)")
    }

    turn++
}

System.err.println("[v2.main] done. last_turn=${memory.campaign.lastTurn}")
```

- [ ] **Step 2: Compile**

Run: `./gradlew :knes-agent:compileKotlin --quiet`
Expected: no errors. Common issue: `session.saveState()` API name — adjust to whatever v1 uses (e.g. `getState()` returning bytes).

- [ ] **Step 3: Commit**

```bash
git add knes-agent/src/main/kotlin/knes/agent/v2/Main.kt
git commit -m "feat(v2): campaign loop — executor + watchdog + reviewer + checkpoints"
```

---

## Phase E — Validation (smoke runs)

### Task E1: Smoke 0 — fresh boot to first overworld turn

- [ ] **Step 1: Ensure Compose UI emulator is running with API server enabled**

User action — start `:knes-compose-ui:run` and click "API Server" in the UI.

- [ ] **Step 2: Run Smoke 0**

Run: `ANTHROPIC_API_KEY=... GEMINI_API_KEY=... ./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=200"`

Expected:
- `~/.knes/runs/<ts>-v2/` exists
- `campaign.json` has `milestones[0].id == "boot"` with status progressing
- `decisions/turn-00001.json`, `turn-00002.json`, ... created
- `snapshots/turn-00001.png`, `turn-00002.png`, ... created (per-iter dump verified)

- [ ] **Step 3: Review outputs**

Inspect: `cat ~/.knes/runs/latest-v2/campaign.json`
Confirm milestones format and at least one PlanEntry written by Advisor.

- [ ] **Step 4: Commit any fixes from smoke**

If snapshot dump missed turns / Cartographer crashed / Advisor JSON parse failed — fix inline; commit per fix:

```bash
git commit -m "fix(v2): <specific smoke 0 issue>"
```

---

### Task E2: Smoke 1 — buy + equip in Coneria

- [ ] **Step 1: Run Smoke 1**

Run: `ANTHROPIC_API_KEY=... GEMINI_API_KEY=... ./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=1500"`

Expected success criteria (from spec §12):
- ≥3/4 weapons bought (`campaign.json` reviews/plans reference buy outcomes)
- ≥3/4 equipped (`turn-*.json` shows `equipWeapon` outcome=ok for 3+ chars)
- Party back on overworld (last few turns show `phase=Overworld`)

- [ ] **Step 2: Diagnose any regressions**

If `EquipWeapon` MenuStuck triggers (known issue per spec §13): acceptable for v2 launch, document in `campaign.json` review entries.

- [ ] **Step 3: Commit fixes**

```bash
git commit -m "fix(v2): <smoke 1 issue>"
```

---

### Task E3: Smoke 2 — grind cycle

- [ ] **Step 1: Run with extended turn budget**

Run: `... -PappArgs="--fresh --max-turns=3000"`

Expected:
- ≥1 encounter triggered + battle won (`battleFightAll` outcome=ok)
- Party returns to safe tile
- Reviewer audit ran ≥1 time (`review.jsonl` not empty)

- [ ] **Step 2: Commit fixes**

---

### Task E4: Smoke 3 — resume

- [ ] **Step 1: Run Smoke 2 with kill**

Start Smoke 2; kill `runV2` after T200 (`Ctrl-C`).

- [ ] **Step 2: Resume**

Run: `... -PappArgs="--resume=$HOME/.knes/runs/latest-v2"`

Expected:
- `[v2.resume] restored T200 (last_turn=NNN)` in stderr
- Advisor's first replan does NOT plan from boot — references campaign history
- Loop continues toward grind milestone

- [ ] **Step 3: Commit fixes**

---

### Task E5: A/B vs v1

- [ ] **Step 1: Run v1 to milestone `buy_weapons.done`**

Run: `./gradlew :knes-agent:run` with logging. Record: turns-to-done, total USD, vision calls.

- [ ] **Step 2: Run v2 to same milestone**

Run: `./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=2000"`. Record same metrics.

- [ ] **Step 3: Write comparison summary**

Create `docs/superpowers/notes/2026-05-NN-v2-vs-v1-smoke.md` with the table (turns / cost / vision calls / skill ok=true rate). Commit.

```bash
git add docs/superpowers/notes/2026-05-NN-v2-vs-v1-smoke.md
git commit -m "docs(v2): smoke A/B vs v1 results"
```

---

## Phase F — Cleanup & PR

### Task F1: Final review and PR

- [ ] **Step 1: Re-run all v2 tests**

Run: `./gradlew :knes-agent:test --tests 'knes.agent.v2.*' --quiet`
Expected: green.

- [ ] **Step 2: Re-verify v1 still works**

Run: `./gradlew :knes-agent:test --quiet` (all tests).
Expected: green — no v1 regressions.

- [ ] **Step 3: Open PR**

Create a feature branch (if not already on one), push, open PR titled `knes-agent v2: PDF architecture (Cartographer/Advisor/Executor/Reviewer)`. Body references the spec and the smoke A/B results note.

---

## Notes for the implementer

- **Per-iter PNG dump is non-negotiable** — `feedback_per_iter_screenshots.md` invariant. If any code path skips it, fix before next smoke.
- **Locate-party-first in vision prompts** — `feedback_locate_party_first.md`. Cartographer + any vision call in v2.
- **EquipWeapon MenuStuck is a known v1 bug** — v2 inherits it. Don't try to fix here; document the failure mode in smoke notes and schedule advisor-rewrite as a follow-up phase (analog to BuyAtShop V5.44→V5.45 in v1).
- **No savestate-hash-keyed flags** — `feedback_no_savestate.md`. LandmarkMemory-only.
- **Atomic writes everywhere** — every JSON file uses tmp+rename. Never write `campaign.json` mid-flight without it.
- **HaikuClient + SonnetClient are currently thin** — they only hold model IDs. The first cut of ExecutorAgent/ReviewerAgent uses placeholders for the LLM call (`invokeHaiku` returns `{"actions":[]}`; `askLlm` falls back to plan tail). This is intentional: get the orchestration loop running with deterministic-ish behavior first, then layer real Sonnet/Haiku calls in a follow-up phase once Smoke 0–2 prove the wiring is sound. The reviewer/executor will degrade gracefully (no actions taken; plan executed verbatim) — exactly the kind of safe failure mode we want during bring-up.
