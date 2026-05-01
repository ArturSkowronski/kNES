# FF1 Koog Agent V2 — Design Spec

**Date:** 2026-05-01
**Status:** Draft, pending review
**Builds on:** V1 design (`2026-04-30-ff1-koog-agent-design.md`) and research (`../research/2026-05-01-llm-game-agents.md`)

## 0. TL;DR

V1 ships a working pipeline that crashes through Anthropic + Koog + emulator end-to-end and autonomously presses START on the title screen, but every outer turn hits Koog's `reActStrategy` 10-iteration cap because the executor loops analyzing RAM instead of committing to actions. Cost is ~$20-50 per attempted Garland run.

V2 replaces the inner loop, adds a small Voyager-style skill library so the agent issues *macro* decisions instead of low-level button presses, wires Anthropic prompt caching, and routes models per phase. Goal scope C: drive **boot → start of Garland battle** autonomously. Defeating Garland in the resulting battle is V3.

V2 is also explicitly designed as a **way-station to V3 / "scope C"** of the research's recommendations: tool-surface reduction to ≤4 tools (Claude-Plays-Pokémon-style) and accordion summarization with `LongTermMemory` are deferred but their API seams are introduced now so they slot in incrementally.

## 1. Goal (scope C)

Acceptance: on a clean checkout with `ANTHROPIC_API_KEY` set, `./gradlew :knes-agent:run --args="--rom=… --profile=ff1 --max-steps=200"` deterministically:

1. Boots, loads ROM, applies `ff1` profile.
2. Drives title → NEW GAME → name party → walks out of Coneria castle → walks to bridge tile → encounter trigger fires → enters Garland battle.
3. Reports `Outcome.AtGarlandBattle` (new outcome) when `RamObserver` first observes `Battle(enemyMainType=GARLAND_ID, …)`.
4. Cost ≤ **$3** per successful run (down from V1's $20-50, achieved via prompt caching + skill library reducing LLM call count).
5. Total wall-clock ≤ **15 minutes**.

V2 does **not** require winning the Garland battle. The agent may report `Outcome.AtGarlandBattle` and exit successfully even if subsequent combat would fail.

## 2. What changes vs V1

Five focused changes, all from research §8 recommendations:

| # | Change | Why |
|---|---|---|
| 1 | Replace `reActStrategy` with `singleRunStrategy`; outer loop owns iteration | Eliminates iteration-cap-as-default failure mode |
| 2 | Voyager-style scripted skill library (3 new skills) | Most boot-to-Garland progression is deterministic; LLM should choose *which* skill, not *how* to press buttons |
| 3 | Long-lived `AnthropicLLMClient` + cache breakpoints | 5-10× cost reduction (research cites ProjectDiscovery 59-70% savings) |
| 4 | Phase-aware model routing | Haiku for known-easy phases, Sonnet for normal, Opus only for advisor escalation |
| 5 | Reduce Koog-visible tool surface from 13 to 7 | Less choice paralysis; raw step/tap/sequence still exist but only skills call them |

Deferred to V3 (with seams introduced in V2 so it's incremental, not a rewrite):

- Accordion summarization of conversation history every N turns.
- Koog `LongTermMemory` with FF1-specific `Concept`s.
- Tool-surface reduction to ~3 macro tools (button-sequence, knowledge-base, navigator) à la Claude Plays Pokémon.
- Reflexion-style self-criticism loop.

## 3. Architecture

```
knes-agent/
├── skills/                                 ← NEW
│   ├── Skill.kt                           ← interface: name, @Tool, internal driver
│   ├── PressStartUntilOverworld.kt        ← title → NEW GAME → bootFlag flip
│   ├── CreateDefaultParty.kt              ← scripted party creation (4× FIGHTER default)
│   ├── WalkOverworldTo.kt                 ← BFS over tile grid; reads worldX/worldY each step
│   ├── SkillRegistry.kt                   ← assembles skills + existing GameActions into one ToolSet
│   └── (re-exports BattleFightAll, WalkUntilEncounter via SkillRegistry)
│
├── llm/                                    ← NEW
│   ├── AnthropicSession.kt                ← long-lived AnthropicLLMClient, owns cache breakpoints
│   ├── ModelRouter.kt                     ← FfPhase → AnthropicModels.* + role-specific params
│   └── PromptCacheConfig.kt               ← Koog cache wiring (system + tool defs are static blocks)
│
├── runtime/
│   ├── AgentSession.kt                    ← MODIFIED: outer loop is now the only loop
│   ├── Outcome.kt                         ← MODIFIED: + AtGarlandBattle
│   ├── PhaseTransition.kt                 ← NEW: typed transitions detected from RAM diffs
│   └── (Trace.kt, SuccessCriteria.kt unchanged)
│
├── executor/ExecutorAgent.kt              ← MODIFIED: singleRunStrategy, single Koog AIAgent.run per outer turn
├── advisor/AdvisorAgent.kt                ← MODIFIED: also singleRunStrategy, lighter prompt
└── perception/RamObserver.kt              ← MODIFIED: detect NameEntry, NewGameMenu phases
```

The advisor and executor agents survive — V2 keeps the Plan-and-Solve split because research §3 supports it for game-playing tasks. They become *single-tool-call-per-LLM-invocation* agents driven by `AgentSession`'s outer loop.

## 4. Single-call inner loop

### Why ReAct ran away in V1

ReAct ([Yao 2022, arXiv:2210.03629](https://arxiv.org/abs/2210.03629)) interleaves "Thought → Act → Observation" within a single LLM-driven loop. The strategy implementation in Koog ([`ai.koog.agents.ext.agent.reActStrategy`](https://github.com/JetBrains/koog/blob/develop/agents/agents-ext/src/commonMain/kotlin/ai/koog/agents/ext/agent/ReActStrategy.kt), see also the user-facing [predefined strategies doc](https://github.com/JetBrains/koog/blob/develop/docs/docs/predefined-agent-strategies.md)) keeps invoking the LLM on the same conversation until either:

1. The LLM returns plain text without a tool call (the agent has "decided to stop"), or
2. The configured `maxIterations` is hit, in which case `AIAgentMaxNumberOfIterationsReachedException` is thrown.

In V1 we observed the second branch every outer turn. Section 6 of [the original ReAct paper](https://arxiv.org/abs/2210.03629) and [Reflexion (Shinn 2023, arXiv:2303.11366)](https://arxiv.org/abs/2303.11366) §4.1 both note that ReAct without an external termination signal **diverges on long-horizon partially-observable tasks** — the model finds reasons to keep examining state instead of acting. This is exactly what we observed: the executor kept calling `getState` and producing analysis paragraphs ("goldLow=144 + goldMid×256 = 400 GP") instead of pressing buttons.

### Switch to `singleRunStrategy`

[Koog 0.5.1 ships `singleRunStrategy`](https://github.com/JetBrains/koog/blob/develop/docs/docs/predefined-agent-strategies.md#single-run-strategy). It does exactly one LLM call per `agent.run(input)`. If the LLM returns a tool call, Koog executes the tool and returns the tool's result as the agent's final output. If the LLM returns plain text, that text is the output. Either way: **one round-trip, deterministic termination**.

This shifts ownership of the "outer ReAct loop" from Koog into our `AgentSession`. Our outer loop already had RAM-driven termination conditions (success criteria, party-defeated, budget). V2 adds: per-skill-invocation budget bump, sliding-window cache breakpoint refresh (§6), phase-transition watchdog.

### Why this matters for cost

Single-call inner means we pay for **one** LLM invocation per outer turn instead of up to 10. Combined with prompt caching (§6), V2's expected per-turn cost drops from ~$0.30-0.50 (V1 measured) to ~$0.02-0.05.

### Outer loop shape (V2)

```
loop:
  phase = observer.observe()
  ram = observer.snapshot()

  outcome = SuccessCriteria.evaluate(phase)
  if outcome != InProgress: terminate(outcome)

  if phase changed OR idleTurns >= IDLE_LIMIT:
    advisor.consult(...) → currentPlan        // single Koog call, may invoke getState/getScreen
    idleTurns = 0

  result = executor.invoke(buildInput(currentPlan, phase, ramDiff))   // single Koog call, single tool invocation

  trace.record(turn, phase, model, tokens, tool, result)

  newRam = observer.snapshot()
  idleTurns = if (newRam ramEquivalent lastRam) idleTurns + 1 else 0
  skillsInvoked += if (result.invokedTool) 1 else 0
  costSoFar += result.tokens.estimatedCostUsd

  if (skillsInvoked >= SKILL_BUDGET || costSoFar >= COST_CAP || elapsed >= WALL_CLOCK_CAP):
    terminate(OutOfBudget)
```

`ramEquivalent` is a custom comparator that ignores noisy fields like frame counter and PRNG state but checks `worldX/worldY/screenState/char[1..4]_status/enemyMainType/...`.

### Code-level changes

`ExecutorAgent.newAgent()`:

```kotlin
private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
    promptExecutor = anthropicSession.executor,            // long-lived, cache-aware
    llmModel = modelRouter.modelFor(phase, AgentRole.EXECUTOR),
    toolRegistry = registry,
    strategy = singleRunStrategy(name = "ff1_executor"),   // <-- was reActStrategy(1, name)
    systemPrompt = ff1ExecutorSystemPrompt,
)
```

Same shape change in `AdvisorAgent`. Drop `maxIterations`. Drop our V1 `try/catch (AIAgentMaxNumberOfIterationsReachedException)` — it can't fire under `singleRunStrategy`.

### Unit testability

`singleRunStrategy` is more amenable to mocking than `reActStrategy`. We can inject a stub `PromptExecutor` returning a fixed tool call and assert that `executor.invoke(...)` produces the expected `EmulatorToolset` side effect. V2 plan adds `ExecutorAgentTest` and `AdvisorAgentTest` exercising this path with no live API calls.

## 5. Skill library

### Why skills

[Voyager (NVIDIA, 2023, arXiv:2305.16291)](https://arxiv.org/abs/2305.16291) demonstrated that an LLM agent that builds and reuses a *skill library* — named, executable code units indexed by description — beats a vanilla ReAct agent by **15.3× on tech-tree progression** in Minecraft. The Voyager paper §3 frames skills as "an executable program containing all the actions necessary to complete a task," with the LLM acting as a "skill author and skill invoker." Hershey's [Claude Plays Pokémon harness](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic) embeds the same idea more conservatively: the `navigator` tool is a hand-coded skill that the LLM never has to re-derive.

For FF1 V2 we hand-write the initial skill set (we don't yet need Voyager's automatic-skill-authoring layer; that's a V3+ idea). Each skill is **scripted Kotlin code** that drives the emulator deterministically. The LLM picks which skill to invoke; the skill's body costs zero LLM tokens.

### Skill interface

```kotlin
package knes.agent.skills

import knes.agent.tools.results.StatusResult

interface Skill {
    val id: String
    val description: String   // surfaced as the @LLMDescription text
    suspend fun invoke(args: Map<String, String> = emptyMap()): SkillResult
}

data class SkillResult(
    val ok: Boolean,
    val message: String,
    val framesElapsed: Int = 0,
    val ramAfter: Map<String, Int> = emptyMap(),
)
```

Skills are registered into `SkillRegistry`, a `ToolSet` whose `@Tool`-annotated methods the LLM sees. The skill registry lazily binds to the live `EmulatorToolset` so skills can drive the emulator without piping references around.

### `pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult`

**Goal:** advance from the title screen / menu through NEW GAME until `bootFlag == 0x4D` (the in-game indicator from `knes-debug/src/main/resources/profiles/ff1.json`, line 28).

**Strategy:** tap START with a 30-frame gap; check RAM after each tap; if `bootFlag` flipped, return success; otherwise repeat up to `maxAttempts`.

**Sketch:**

```kotlin
class PressStartUntilOverworld(private val toolset: EmulatorToolset) : Skill {
    override val id = "press_start_until_overworld"
    override val description = "Tap START until the game leaves the title/menu state and reaches in-game (FF1 bootFlag = 0x4D). Bounded by maxAttempts."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxAttempts = args["maxAttempts"]?.toIntOrNull() ?: 60
        var attempts = 0
        var totalFrames = 0
        while (attempts < maxAttempts) {
            val tap = toolset.tap("START", count = 1, pressFrames = 5, gapFrames = 30)
            totalFrames += tap.frame
            attempts++
            val state = toolset.getState()
            if (state.ram["bootFlag"] == 0x4D) {
                return SkillResult(ok = true, message = "Reached in-game after $attempts taps", framesElapsed = totalFrames, ramAfter = state.ram)
            }
        }
        return SkillResult(ok = false, message = "bootFlag never flipped after $maxAttempts START taps", framesElapsed = totalFrames)
    }
}
```

**Edge cases:** intro cinematic (sometimes needs A/B presses, not just START); we handle by tapping `START` first and only if 10 attempts pass without progress, falling back to `A`. Speedrun community calls this the [intro skip pattern](https://www.speedrun.com/final_fantasy_nes/guides/vk3vf).

### `createDefaultParty(classes: List<String>, names: List<String>?): SkillResult`

**Goal:** complete character creation: pick 4 classes, name each character, confirm.

**FF1 class indices** (from FF1 disassembly community; see [datacrystal.tcrf.net FF1 NES](https://datacrystal.tcrf.net/wiki/Final_Fantasy_(NES)) and the GameFAQs class table):

```
0 = Fighter (FIGHTER)
1 = Thief (THIEF)
2 = Black Belt (BLACK_BELT)
3 = Red Mage (RED_MAGE)
4 = White Mage (WHITE_MAGE)
5 = Black Mage (BLACK_MAGE)
```

**Strategy** (sequential, no branching needed):

1. From overworld-after-NewGame menu: navigate to `New Game` confirm. (handled by `pressStartUntilOverworld` already if it lands here, but if it lands one screen earlier we add the second tap.)
2. For each of the 4 character slots:
   1. Use UP/DOWN to position cursor at desired class (read `menuCursor` to confirm position).
   2. Press A to select class.
   3. Use UP/DOWN/LEFT/RIGHT in name-entry grid to spell the name (default: "H1"–"H4" — short to keep this fast). Press A on each letter, then on END.
   4. Confirm class+name (A).
3. After fourth character, confirm whole party (A on the YES button).

**Termination check:** all four `char[1..4]_status` values transition from 0xFF (uninitialized) to 0x00 (alive, no status). Ramdump signature confirms party exists in RAM ([TASvideos FF1 RAM map](https://tasvideos.org/GameResources/NES/FinalFantasy1)).

**Default classes:** `["FIGHTER", "FIGHTER", "WHITE_MAGE", "BLACK_MAGE"]` — solid balanced party for early game per the [GameFAQs class guide](https://gamefaqs.gamespot.com/nes/522595-final-fantasy/faqs/3290).

**Implementation note:** the name-entry grid layout is a fixed 6×6 (or so) grid of letters; we hardcode the screen-coordinate-to-letter mapping. Documented at [strategywiki.org/wiki/Final_Fantasy/Walkthrough](https://strategywiki.org/wiki/Final_Fantasy/Walkthrough).

### `walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 200): SkillResult`

**Goal:** BFS-pathfind from current `(worldX, worldY)` to target tile.

**Walkable tile data:** FF1 overworld is a 256×256 tile map. The walkable-vs-blocked classification comes from the world map's tile type table at ROM offset `0x40000` (per the [FF1 disassembly](https://github.com/SubtleAlchemist/ff1-disasm)). We extract this once into a `walkableTiles: Set<Int>` constant.

Alternative if ROM extraction proves brittle: hardcode the **path** from Coneria castle exit to the bridge tile (it's a single fixed sequence of ~40 tiles, well-documented in any [speedrun route](https://www.speedrun.com/final_fantasy_nes/guides/vk3vf)). For V2 hardcoded path is simpler and probably sufficient for goal scope C.

**Sketch (path-based variant):**

```kotlin
class WalkOverworldTo(private val toolset: EmulatorToolset) : Skill {
    override val id = "walk_overworld_to"
    override val description = "Walk overworld from current (worldX, worldY) toward (targetX, targetY). Each step holds a direction button for 16 frames. Aborts on encounter (screenState=0x68)."

    private val FRAMES_PER_TILE = 16   // FF1 walking speed; confirmed in TASvideos guide

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args.getValue("targetX").toInt()
        val ty = args.getValue("targetY").toInt()
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 200
        var stepsTaken = 0
        while (stepsTaken < maxSteps) {
            val state = toolset.getState()
            val ram = state.ram
            if (ram["screenState"] == 0x68) {
                // Encounter triggered — that's a normal exit for this skill.
                return SkillResult(ok = true, message = "Encounter triggered after $stepsTaken steps", ramAfter = ram)
            }
            val cx = ram["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ram["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "Reached ($tx, $ty) in $stepsTaken steps", ramAfter = ram)
            }
            val dir = pickDirection(cx, cy, tx, ty)
            toolset.step(buttons = listOf(dir), frames = FRAMES_PER_TILE)
            stepsTaken++
        }
        return SkillResult(false, "Did not reach ($tx, $ty) in $maxSteps steps")
    }

    private fun pickDirection(cx: Int, cy: Int, tx: Int, ty: Int): String = when {
        tx > cx -> "RIGHT"; tx < cx -> "LEFT"; ty > cy -> "DOWN"; else -> "UP"
    }
}
```

For V2 scope C the only `walkOverworldTo` invocation we need is "to the bridge tile" — likely `(0xC4, 0x96)` or thereabouts; we'll confirm during implementation. Hardcoded plan if BFS proves unreliable: a list of `(direction, tile-count)` pairs the skill executes.

### Re-exposed existing actions via skill registry

`BattleFightAll` and `WalkUntilEncounter` already exist in `knes-debug/src/main/kotlin/knes/debug/actions/ff1/` and are registered through `ActionRegistry`. The skill registry surfaces them as Koog `@Tool` methods alongside the new skills, so the LLM sees one uniform "what skills can I invoke" list. Implementation: `SkillRegistry` constructor wraps each registered `GameAction` for the active profile in a synthetic `Skill` that delegates to `EmulatorToolset.executeAction(profileId, actionId)`.

### Skill registry: what Koog actually sees

`SkillRegistry : ToolSet` exposes exactly these methods (each annotated `@Tool` + `@LLMDescription`):

```
pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult
createDefaultParty(classes: List<String> = …, names: List<String>? = null): SkillResult
walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 200): SkillResult
battleFightAll(): ActionToolResult                      // delegates to GameAction
walkUntilEncounter(): ActionToolResult                  // delegates to GameAction
getState(): StateSnapshot                               // unchanged from V1
askAdvisor(reason: String): String                      // unchanged from V1, lives on advisor side
```

Seven tools. Down from V1's 13. Raw `step / tap / sequence / press / release / loadRom / reset / applyProfile` remain on `EmulatorToolset` (used by skills internally + by the Ktor / MCP layers for Claude Code) but are **not** registered with Koog.

### Skills as path to V3 ("scope C")

In V3 we collapse all skill methods into a single `invokeSkill(name: String, args: Map<String, String>)` tool, dropping the per-skill `@Tool` methods. The skill registry stays intact; only the Koog-visible facade collapses. That brings tool count to 3 (`invokeSkill`, `getState`, `askAdvisor`) — Hershey-equivalent. We don't do this in V2 because typed signatures help the LLM pick the right skill while we're still tuning the set. Once skills stabilize, the typed surface stops earning its keep.

## 6. Prompt caching

### Why this matters

[Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) (also documented at [docs.anthropic.com/en/docs/build-with-claude/prompt-caching](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching)) lets you mark prefix portions of a prompt as cacheable. Cached prefix tokens are charged at **10% of base input price** ([Anthropic pricing](https://platform.claude.com/docs/en/build-with-claude/prompt-caching#pricing)) and can be reused for **5 minutes** by default. ProjectDiscovery reports [59-70% input-cost reduction](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching) on multi-turn tool-using agents; a [related industry write-up](https://www.helicone.ai/blog/prompt-caching) sees similar 5-10× compounding savings on agent loops.

V1 builds a fresh `AnthropicLLMClient` per outer turn (we did this in V1 Task 6.1 to dodge `400 messages: final assistant content cannot end with trailing whitespace` errors that came from `reActStrategy` reusing conversation state). This **completely defeats caching** — every call is a cold prompt.

### V2 caching architecture

```
[ system prompt + tool descriptions ]   ← cache_control: ephemeral; ~1500-2500 tokens, hit rate ~99%
[ "static run preamble"                  ← cache_control: ephemeral; ~400 tokens, hit rate ~90%
   - rom name, profile, goal              (refreshed only when phase class changes)
   - active phase classification         ]
[ "rolling state preamble"                ← uncached; ~150 tokens
   - RAM diff since last turn
   - last skill result
   - current advisor plan                ]
[ "this turn input"                       ← uncached; ~50 tokens
   - "What is the next skill to invoke?" ]
```

Cache breakpoints (Anthropic supports up to 4 per request):

1. **End of system prompt** — biggest win. System prompt + 7 `@Tool` descriptions are static across the whole run. Caches the most expensive tokens.
2. **End of static run preamble** — caches the "what game, what phase" framing across consecutive turns in the same phase. Refreshed when phase class transitions (advisor consultation point anyway).
3. *(reserved for V3)* End of accordion summary — caches the long-term-memory digest.
4. *(reserved for V3)* End of recent-turn history window.

V2 uses breakpoints 1 and 2. V3 fills in 3 and 4 once we add summarization.

### Implementation

`AnthropicSession` (new file `knes-agent/src/main/kotlin/knes/agent/llm/AnthropicSession.kt`):

```kotlin
package knes.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClientSettings
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor

class AnthropicSession(apiKey: String) : AutoCloseable {
    // Single long-lived client; cache headers travel with each request.
    val client: AnthropicLLMClient = AnthropicLLMClient(
        apiKey = apiKey,
        settings = AnthropicLLMClientSettings(
            // Enable extended cache control headers if Koog 0.5.1 exposes them.
            // If not, we drop down to AnthropicAPI directly per §11 risk note.
        ),
    )
    val executor: SingleLLMPromptExecutor = SingleLLMPromptExecutor(client)

    override fun close() {
        // Koog client uses Ktor's CIO; release coroutine resources.
    }
}
```

`PromptCacheConfig` (new file) holds the cache-breakpoint markers used when assembling each turn's prompt. Implementation depends on what Koog 0.5.1's `Prompt` builder exposes — first task of the plan probes this and falls back to direct Anthropic SDK if Koog's wrapper doesn't expose `cache_control` per-message-block.

### What V1 wasted

V1 measured cost (rough): ~$0.30 per outer turn at 50% of cap = ~$0.60 actual; 5 turns observed in the 9-minute run before timeout; extrapolated to ~50-200 turns for a Garland run gives $30-120 worst case. With caching at typical 60-80% hit rate on the system+tool prefix and skill library cutting the per-turn input from ~3000 to ~1000 tokens (because the agent isn't enumerating button-level tools every turn), V2 target is ≤ $0.05 per outer turn → ≤ $3 per Garland run.

`AnthropicSession` lives for the whole `AgentSession.run()`. `ExecutorAgent` and `AdvisorAgent` constructors take it as a dependency (instead of `apiKey: String` as in V1). Both share the same client, so cache breakpoints they place hit each other's lookups.

## 7. Model routing

### Pricing snapshot (per [Anthropic pricing page](https://platform.claude.com/docs/en/about-claude/pricing), 2026-05)

| Model | Input $/MTok | Output $/MTok | Cached input $/MTok | Speed |
|---|---|---|---|---|
| Haiku 4.5 | $1 | $5 | $0.10 | fastest |
| Sonnet 4.5 | $3 | $15 | $0.30 | medium |
| Opus 4 / 4.1 | $15 | $75 | $1.50 | slowest |

Haiku 4.5 is **15× cheaper than Opus on input** and **15× cheaper on output**. Where it's good enough, using it instead of Sonnet/Opus is a free win.

### Routing rules

`ModelRouter.modelFor(phase: FfPhase, role: AgentRole): LLModel`:

| Phase | Executor model | Advisor model | Rationale |
|---|---|---|---|
| `Boot`, `TitleOrMenu`, `NewGameMenu`, `NameEntry` | Sonnet 4.5 | Opus 4 | Visual novelty, less-obvious choices, advisor needs to plan party composition |
| `Overworld` | Haiku 4.5 | Sonnet 4.5 | LLM picks `walkOverworldTo` or `walkUntilEncounter` — easy choice |
| `Battle` | Haiku 4.5 | Sonnet 4.5 | LLM picks `battleFightAll` — trivial choice for V2 (V3 will diversify combat tools) |
| `PostBattle` | Haiku 4.5 | Sonnet 4.5 | Acknowledge XP/loot dialog; same easy pattern |
| Watchdog escalation (advisor consulted because stuck) | n/a | Opus 4 | Hardest cognitive load — diagnosing "why am I stuck" deserves the strongest model |

[PokéLLMon (2024, arXiv:2402.01118)](https://arxiv.org/abs/2402.01118) uses GPT-4 throughout but notes that 80% of moves are "obvious" given visible state, suggesting smaller models suffice for routine play; only the harder turns benefit from the larger model.

### Implementation

`ModelRouter` is a single object/class with a switch on `(phase, role)` returning an `LLModel`. ~30 LOC. Lives at `knes-agent/src/main/kotlin/knes/agent/llm/ModelRouter.kt`.

The `ExecutorAgent` and `AdvisorAgent` constructors take a `ModelRouter` and call `modelRouter.modelFor(currentPhase, role)` at each `newAgent(phase)` invocation. Since `singleRunStrategy` agents are constructed per-call (cheap), routing per-call is essentially free.

### Measurement

V2 trace logs will record (per turn): model used, tokens in, tokens out, cache-hit-tokens, derived USD cost. This data lets us tune the routing table empirically after the first few real runs — e.g., if Haiku 4.5 picks the wrong skill in `Overworld` more than X% of the time, bump `Overworld` executor to Sonnet 4.5.

## 8. New phase classifications

`RamObserver` adds:

- `NewGameMenu` — `bootFlag != 0x4D` AND `screenState == something_specific` (we'll need to observe and document the constant during V2 implementation; see §11 Risks).
- `NameEntry` — character creation; identified by RAM markers from `ff1.json` (likely `menuCursor` + `screenState` combination).

The bootFlag-based `TitleOrMenu` from V1 stays as the catch-all for "pre-game with no more specific signal."

These new phases let advisor and executor produce phase-specific plans without re-deriving game state from screenshots every turn.

## 9. New `Outcome.AtGarlandBattle`

```kotlin
enum class Outcome { InProgress, AtGarlandBattle, Victory, PartyDefeated, OutOfBudget, Error }
```

`SuccessCriteria.evaluate(phase)`:

- `Battle(enemyId = GARLAND_ID, enemyDead = false)` → `AtGarlandBattle` (V2 acceptance)
- `Battle(enemyId = GARLAND_ID, enemyDead = true)` → `Victory` (V3 acceptance, same constant kept for forward-compat)
- `PartyDefeated` → `PartyDefeated`
- everything else → `InProgress`

`AgentSession.run()` returns `AtGarlandBattle` and exits. Trace records the final RAM snapshot and a screenshot for evidence. CLI exits with status 0 (V2 success).

## 10. Cost and time budgets

V2 budgets (CLI flags, defaults):

- `--max-skill-invocations=80` (skills are macro; 80 should suffice for boot→Garland based on speedrun routes).
- `--cost-cap-usd=3` — derived from `tokensIn × $0.003/1k + tokensOut × $0.015/1k` (Sonnet 4.5 prices) with caching modeled. Conservative; abort if exceeded.
- `--wall-clock-cap-seconds=900` (15 min).

Each tracked in `AgentSession`. Exceeding any cap returns `OutOfBudget`.

## 11. Risks and open questions

- **`NewGameMenu` / `NameEntry` RAM signatures.** `ff1.json` documents `screenState`, `menuCursor`, and `bootFlag`, but the exact value combinations for these phases need empirical confirmation during implementation. Plan task: write a small `RamRecorder` that runs the emulator under manual input and logs RAM snapshots at each visual phase, so we can encode the constants from real data instead of guessing.
- **Skill robustness across ROM regions.** `BattleFightAll`, `WalkUntilEncounter`, and our scripted name-entry assume USA / English ROM. PAL/JP would break menu indices. V2 explicitly assumes the standard NES USA ROM.
- **Walkable-tile table for `walkOverworldTo`.** FF1 overworld tile data lives in ROM at known addresses; we hardcode the walkable IDs. Wrong table = walking through ocean. Verify against TASvideos resources during implementation.
- **`singleRunStrategy` may not exist by that exact name in Koog 0.5.1.** Research cites `nodes-and-components.md`. Confirm by jar inspection in the very first plan task; rename if needed.
- **Prompt caching coverage in Koog.** Koog 0.5.1 may or may not expose Anthropic cache breakpoints declaratively. Worst case we configure them at the `AnthropicLLMClient` level and bypass Koog's wrapper. Plan task: probe the API; fall back to direct Anthropic SDK call if Koog lacks support.
- **Determinism of skills under shared mode.** Skills currently target `EmulatorSession()` standalone. If anyone runs the agent in shared mode (with the Compose UI driving frames), skills must not deadlock. The mode-aware `step/tap/sequence` from V1 already handles this — skills inherit it.
- **Garland enemy id (`GARLAND_ID = 0x7C`).** Same uncertainty as V1 — confirm on first observed bridge battle. If wrong, V2 fails to detect `AtGarlandBattle` and runs out of budget.

## 12. Path to V3 ("scope C" of research)

V2 deliberately doesn't try to land all five research recommendations at once. The remaining items become V2.x / V3 increments. Each is contained, shippable on its own, and depends only on V2's seams:

### V2.1 — Accordion summarization

Why: research §1, §3, §4 — Hershey's harness compresses every ~30 actions into a digest while preserving the knowledge base verbatim ([ZenML write-up](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic)). Without this, multi-hour runs blow out the 200k context window.

Hook: `AgentSession.run()` after every N turns invokes a "summarize the last N turns into 200 words" LLM call (Sonnet, cheap). Existing `Trace` already holds the data. New file: `knes-agent/src/main/kotlin/knes/agent/runtime/Summarizer.kt`. Doesn't change V2 architecture.

Effort: small. Marginal impact for V2's scope (boot→Garland is short enough not to need it), big impact for V3 (winning Garland) and beyond.

Reference: Koog's [`nodeLLMCompressHistory`](https://github.com/JetBrains/koog/blob/develop/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/SystemNodes.kt) does exactly this declaratively if we want to lift the loop into a Koog strategy graph — see [docs/docs/agents/graph-based-agents.md](https://github.com/JetBrains/koog/blob/develop/docs/docs/agents/graph-based-agents.md).

### V2.2 — `LongTermMemory` with FF1 `Concept`s

Why: Hershey's `update_knowledge_base` tool gives the LLM explicit memory it can edit. Koog 0.5.1 has [`LongTermMemory`](https://github.com/JetBrains/koog/blob/develop/agents/agents-features/agents-features-longterm-memory/Module.md) that exposes the same pattern declaratively.

Concepts to capture for FF1: `currentObjective`, `gameProgress`, `partyComposition`, `keyItems`, `recentFailures`, `discoveredLocations`. Each has a `FactType` (single-value, list, map). The LLM gets a `recordFact(concept, value)` and `recallFacts(concept)` tool pair instead of stuffing knowledge into prompt history.

Reference: [Module.md](https://github.com/JetBrains/koog/blob/develop/agents/agents-features/agents-features-longterm-memory/Module.md), [Hershey deep-dive](https://michaelyliu6.github.io/posts/claude-plays-pokemon/) on KB design.

### V2.3 — Tool surface reduction to 3 ("Hershey configuration")

Collapse the 7 V2-Koog-visible tools into 3:

```
invokeSkill(name: String, args: Map<String, String>): SkillResult
recordFact(concept: String, value: String): StatusResult
askAdvisor(reason: String): String
```

Plus the implicit `getState`-via-skill-result (every skill result includes `ramAfter`, so explicit `getState` becomes redundant).

The skill registry remains intact; this is a Koog-facing facade swap. Once the skill set has stabilized through real runs, typed signatures buy less than narrow tool count saves in iteration cost (Hershey: "stripped out complexity over time"). We don't do this in V2 because we're still discovering which skills matter.

Code change: replace `SkillRegistry`'s `@Tool`-annotated methods with a single `@Tool fun invokeSkill(...)` that internally dispatches on `name`. ~50 LOC.

### V2.4 — Reflexion self-criticism on watchdog escalation

Why: research §3 cites Reflexion ([Shinn 2023, arXiv:2303.11366](https://arxiv.org/abs/2303.11366)) for long-horizon agents. Hershey's secondary-LLM KB review is the same idea.

Hook: when `AgentSession`'s `idleTurns >= IDLE_LIMIT` (already detected in V1), instead of just re-prompting the advisor with the latest observation, prepend a "reflect on what went wrong in the last N turns" turn first. The advisor's output is one paragraph of self-criticism that gets injected into the executor's next prompt.

This is Reflexion's "verbal reinforcement" applied to our existing escalation point. ~30 LOC in `AgentSession`.

### V2.5 — Voyager-style automatic skill authoring (V3+)

Why: Voyager's killer feature is the LLM **writing new skills** when current ones fail. We could do the same: when a `walkOverworldTo` invocation fails, the advisor proposes a Kotlin code snippet that becomes a new `Skill`, gets compiled, and added to the registry.

Out of scope for V2/V2.x. Listed for completeness because the skill registry abstraction is the prerequisite — V2's `Skill` interface is sufficient as a mounting point.

Reference: [Voyager paper §3.2](https://arxiv.org/abs/2305.16291), specifically the iterative-prompting + self-verification loop. Open-source implementation at [github.com/MineDojo/Voyager](https://github.com/MineDojo/Voyager).

### Sequencing

V2 → V2.1 (summarization) → V2.2 (LongTermMemory) → V2.3 (3-tool surface) → V2.4 (Reflexion). V2.5 only after V2.4 demonstrably saves runs that V2 lost.

Each step is its own brainstorm-spec-plan-execute cycle. Each is shippable. Each preserves backwards compatibility with the V2 base — we're decorating, not rewriting.

## 13. Acceptance test

1. Fresh checkout on master post-V2-merge.
2. `ANTHROPIC_API_KEY` set, `roms/ff.nes` present.
3. `./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes --profile=ff1"`.
4. Headless. Terminal logs phase transitions: `TitleOrMenu → NewGameMenu → NameEntry → Overworld(starting tile) → Overworld(bridge tile) → Battle(Garland)`.
5. Final line: `OUTCOME: AtGarlandBattle`. Exit code 0.
6. Cost printed at end ≤ $3. Wall-clock ≤ 15 min.
7. `runs/<ts>/trace.jsonl` exists and contains the path. Screenshot at `Battle(Garland)` saved as evidence.
8. Existing Claude Code MCP flow unchanged.

## 14. References

### Internal

- **Research dossier** (motivates every change here): [`../research/2026-05-01-llm-game-agents.md`](../research/2026-05-01-llm-game-agents.md)
- **V1 spec**: [`./2026-04-30-ff1-koog-agent-design.md`](./2026-04-30-ff1-koog-agent-design.md)
- **V1 plan** (for reference of what each task touched): [`../plans/2026-05-01-ff1-koog-agent.md`](../plans/2026-05-01-ff1-koog-agent.md)
- **FF1 system prompt** (Claude Code MCP): [`../../ff1-system-prompt.md`](../../ff1-system-prompt.md) — V2's executor system prompt is a pruned variant of this; keep them aligned.
- **FF1 RAM profile**: [`knes-debug/src/main/resources/profiles/ff1.json`](../../../knes-debug/src/main/resources/profiles/ff1.json)

### Papers

- **ReAct** — Yao et al. 2022, [arXiv:2210.03629](https://arxiv.org/abs/2210.03629). The strategy V1 used and V2 replaces.
- **Reflexion** — Shinn et al. 2023, [arXiv:2303.11366](https://arxiv.org/abs/2303.11366). Verbal-reinforcement for long-horizon agents; basis for V2.4.
- **Voyager** — Wang et al. 2023, [arXiv:2305.16291](https://arxiv.org/abs/2305.16291). Skill library + curriculum; basis for V2 §5.
- **Plan-and-Solve** — Wang et al. 2023, [arXiv:2305.04091](https://arxiv.org/abs/2305.04091). Justifies the advisor/executor split.
- **PokéLLMon** — Hu et al. 2024, [arXiv:2402.01118](https://arxiv.org/abs/2402.01118). LLM-based Pokémon battle agent; informs §7 model routing.
- **Tree of Thoughts** — Yao et al. 2023, [arXiv:2305.10601](https://arxiv.org/abs/2305.10601). Cited in research §3 for branching reasoning; not used in V2.

### Anthropic

- [**Prompt caching official docs**](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) — pricing, breakpoints, TTL.
- [**Pricing page**](https://platform.claude.com/docs/en/about-claude/pricing) — per-model rates, cached-input rate.
- [**Tool use overview**](https://platform.claude.com/docs/en/build-with-claude/tool-use) — schema constraints relevant to V2's typed skill methods.
- [**Models overview**](https://platform.claude.com/docs/en/about-claude/models) — Haiku 4.5, Sonnet 4.5, Opus 4 / 4.1 capabilities and intended use cases.

### Koog (JetBrains)

- **Repo**: [github.com/JetBrains/koog](https://github.com/JetBrains/koog).
- **Predefined strategies** (incl. `singleRunStrategy`, `reActStrategy`): [`docs/docs/predefined-agent-strategies.md`](https://github.com/JetBrains/koog/blob/develop/docs/docs/predefined-agent-strategies.md).
- **Functional agents** (alternative strategy DSL): [`docs/docs/agents/functional-agents.md`](https://github.com/JetBrains/koog/blob/develop/docs/docs/agents/functional-agents.md).
- **Graph-based agents**: [`docs/docs/agents/graph-based-agents.md`](https://github.com/JetBrains/koog/blob/develop/docs/docs/agents/graph-based-agents.md).
- **Custom subgraphs**: [`docs/docs/custom-subgraphs.md`](https://github.com/JetBrains/koog/blob/develop/docs/docs/custom-subgraphs.md). Useful for V2.4+ if we lift the outer loop into a strategy graph.
- **Long-term memory**: [`agents/agents-features/agents-features-longterm-memory/Module.md`](https://github.com/JetBrains/koog/blob/develop/agents/agents-features/agents-features-longterm-memory/Module.md). Basis for V2.2.
- **MCP integration**: [`agents/agents-mcp/Module.md`](https://github.com/JetBrains/koog/blob/develop/agents/agents-mcp/Module.md).
- **Anthropic client**: [`prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/Module.md`](https://github.com/JetBrains/koog/blob/develop/prompt/prompt-executor/prompt-executor-clients/prompt-executor-anthropic-client/Module.md).
- **`nodeLLMCompressHistory`** (built-in summarization): [`agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/SystemNodes.kt`](https://github.com/JetBrains/koog/blob/develop/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/SystemNodes.kt). Basis for V2.1 if we go declarative.

### Claude Plays Pokémon

- **Anthropic Twitch stream**: [twitch.tv/claudeplayspokemon](https://twitch.tv/claudeplayspokemon).
- **Hershey MLOps podcast write-up (ZenML)**: [zenml.io/llmops-database/...](https://www.zenml.io/llmops-database/building-and-deploying-a-pokemon-playing-llm-agent-at-anthropic). Primary architectural source.
- **Liu deep-dive**: [michaelyliu6.github.io/posts/claude-plays-pokemon](https://michaelyliu6.github.io/posts/claude-plays-pokemon/). Detail on tools, KB schema, failure modes.
- **HN discussion**: [news.ycombinator.com/item?id=43173825](https://news.ycombinator.com/item?id=43173825). Independent observations on visual misidentification and stuck-in-Mt-Moon.
- **Anthropic announcement (Feb 2025)**: [x.com/AnthropicAI/status/1894419042150027701](https://x.com/AnthropicAI/status/1894419042150027701).
- **TechCrunch summary**: [techcrunch.com/2025/02/25/anthropics-claude-ai-is-playing-pokemon-on-twitch-slowly](https://techcrunch.com/2025/02/25/anthropics-claude-ai-is-playing-pokemon-on-twitch-slowly/).

### FF1 game data and routes

- **TASvideos FF1 RAM map**: [tasvideos.org/GameResources/NES/FinalFantasy1](https://tasvideos.org/GameResources/NES/FinalFantasy1). Authoritative RAM addresses; supplements `ff1.json`.
- **datacrystal.tcrf.net FF1 NES**: [datacrystal.tcrf.net/wiki/Final_Fantasy_(NES)](https://datacrystal.tcrf.net/wiki/Final_Fantasy_(NES)). ROM offsets, item tables, encounter tables.
- **Speedrun.com FF1 NES routes**: [speedrun.com/final_fantasy_nes](https://www.speedrun.com/final_fantasy_nes). Optimal route from boot through Garland and beyond.
- **Speedrun guide (any% NES)**: [speedrun.com/final_fantasy_nes/guides/vk3vf](https://www.speedrun.com/final_fantasy_nes/guides/vk3vf). Title-skip pattern, party choice, tile-level walking path.
- **GameFAQs class guide**: [gamefaqs.gamespot.com/nes/522595-final-fantasy/faqs/3290](https://gamefaqs.gamespot.com/nes/522595-final-fantasy/faqs/3290). Class indices and starting stats.
- **StrategyWiki walkthrough**: [strategywiki.org/wiki/Final_Fantasy/Walkthrough](https://strategywiki.org/wiki/Final_Fantasy/Walkthrough). Name-entry grid layout, NPC dialogs, menu navigation.
- **FF1 disassembly**: [github.com/SubtleAlchemist/ff1-disasm](https://github.com/SubtleAlchemist/ff1-disasm). Source-of-truth for ROM internals if we extract walkable-tile tables.

### Industry write-ups on agent cost / caching

- **ProjectDiscovery — 59-70% prompt-caching savings**: [projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching](https://projectdiscovery.io/blog/how-we-cut-llm-cost-with-prompt-caching).
- **Helicone — caching best practices**: [helicone.ai/blog/prompt-caching](https://www.helicone.ai/blog/prompt-caching).

### Additional agent frameworks for context

- **AutoGPT**: [github.com/Significant-Gravitas/AutoGPT](https://github.com/Significant-Gravitas/AutoGPT). The canonical "give an LLM a goal and let it loop" framework; informs anti-patterns.
- **LangGraph**: [langchain-ai.github.io/langgraph](https://langchain-ai.github.io/langgraph/). State-graph-based agents; conceptually similar to Koog's graph strategies.
- **CrewAI**: [crewai.com](https://www.crewai.com/). Multi-agent orchestration; informs our advisor/executor + future reflection split.
