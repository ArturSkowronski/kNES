# FF1 Koog Agent — Design Spec

**Date:** 2026-04-30 (updated 2026-05-01 with post-implementation reality)
**Status:** Implemented through Phase 5 (acceptance run = Task 6.1 still pending)
**Modules:** new `knes-agent` and `knes-agent-tools` (refactor extracting shared `EmulatorToolset`)

## 1. Goal

Build an autonomous agent, written in Kotlin and powered by [Koog](https://github.com/JetBrains/koog), that plays Final Fantasy (NES, 1987) in the kNES emulator and defeats the first boss (Garland) using Anthropic models — replicating, in-process, the role currently filled by Claude Code over MCP.

The agent is a self-contained alternative client to the same emulator surface today exposed by `knes-mcp` and `knes-api`. No part of this spec changes how Claude Code connects.

## 2. Success criteria

V1 is done when, on a developer machine with `ANTHROPIC_API_KEY` set, the command

```
./gradlew :knes-agent:run
```

deterministically:

1. Boots the emulator, loads `roms/ff1.nes`, applies the `ff1` RAM profile.
2. Drives the title → NEW GAME → party creation → world map → Coneria → bridge → Garland flow with no human input.
3. Detects victory: Garland HP reaches 0 in the FF1 RAM profile (`battle.enemy_hp[0]`), and the agent reports `Outcome.Victory` from `AgentSession.run()`.
4. Reports failure cleanly on party wipe (`Outcome.PartyDefeated`), step-budget exhaustion (`Outcome.OutOfBudget`), or unrecoverable tool error (`Outcome.Error`).

V1 explicitly accepts that the agent may need multiple attempts (the executor’s strategy is non-deterministic). One successful run on a clean checkout, recorded as a trace log, is the acceptance bar.

## 3. Architecture overview

Two coordinated changes:

**(a) Refactor — shared `EmulatorToolset`.** Today, MCP tools forward HTTP calls to the REST API; tool logic effectively lives across `ApiServer` route handlers and the underlying session classes. We pull tool surfaces into a single annotated class that both the agent (in-process via Koog `ToolRegistry`) and the MCP server (in-process delegation, replacing the REST roundtrip) call directly. Result: one source of truth for tool names, params, semantics — same FF1 system prompt works for both clients.

**(b) New module — `knes-agent`.** Embeds a Koog Advisor/Executor agent loop, perception layer, and runtime that owns the outer success/escalation logic.

```
knes-agent-tools/                              ← NEW shared module (extracted)
└── src/main/kotlin/knes/agent/tools/
    ├── EmulatorToolset.kt               ← @Tool / @LLMDescription, typed params/results
    ├── results/                         ← StepResult, TapResult, StateSnapshot, ScreenPng…
    └── KoogToolToMcpSchema.kt           ← reflection adapter: @Tool methods → MCP ToolSchema

knes-mcp/                                ← MODIFIED
└── McpServer.kt                          ← delegates to EmulatorToolset directly (no HTTP)

knes-api/                                ← MODIFIED
└── ApiServer.kt                          ← Ktor handlers shrink to parse → toolset.x() → serialize

knes-agent/                              ← NEW module
└── src/main/kotlin/knes/agent/
    ├── tools/EmulatorBackend.kt         ← thin adapter: ToolRegistry registration of EmulatorToolset
    ├── perception/RamObserver.kt        ← parses FF1 phase from RAM (title / overworld / dungeon / battle)
    ├── perception/ScreenshotPolicy.kt   ← decides when to attach an image to executor turns
    ├── advisor/AdvisorAgent.kt          ← Koog AIAgent, single-shot, Opus, returns plan text
    ├── executor/ExecutorAgent.kt        ← Koog AIAgent, reActStrategy, Sonnet, calls EmulatorToolset
    │                                      and the advisor (registered as a tool)
    ├── runtime/AgentSession.kt          ← outer loop: watchdog, success detection, budget
    ├── runtime/Trace.kt                 ← jsonl trace of every turn (for replay/debug)
    └── Main.kt                          ← CLI entry: load ROM, apply profile, run session
```

Module dependencies (as actually implemented):

- **`knes-emulator-session`** — extracted during Task 1.4 to break a `knes-api` ↔ `knes-agent-tools` cycle. Holds `EmulatorSession`, `ApiController`, `InputQueue`, `StepRequest`, `SessionActionController`. Plain JVM, no Ktor.
- `knes-agent-tools` depends on `:knes-emulator`, `:knes-controllers`, `:knes-debug`, `:knes-emulator-session`, plus `ai.koog:agents-core` + `ai.koog:agents-tools` (the `ToolSet` interface lives in `agents-tools`, not `agents-core`).
- `knes-api` depends on `:knes-agent-tools` and `:knes-emulator-session`. Routes delegate to `EmulatorToolset` (323 LOC vs ~350 baseline).
- `knes-mcp` depends on `:knes-agent-tools`. Default `createMcpServer()` constructs an in-process `EmulatorSession` + `EmulatorToolset` and registers tools by hand-mapping MCP args to typed methods. `--remote` flag preserves the legacy REST-bridge (`createRemoteMcpServer()` in `RemoteRestBridge.kt`).
- `knes-agent` depends on `:knes-emulator`, `:knes-controllers`, `:knes-debug`, `:knes-agent-tools`, `:knes-emulator-session`, plus Koog: `agents-core:0.5.1`, `agents-mcp:0.5.1`, `agents-ext:0.5.1`, `prompt-executor-anthropic-client:0.5.1`, `prompt-executor-llms-all:0.5.1`. **No** dep on `:knes-api` or `:knes-mcp`.
- All modules bumped to JDK toolchain **17** (Koog 0.5.1 jars are class file v61).

Resolved Koog 0.5.1 API surface (canonical for this codebase):

| Construct | Path |
|---|---|
| Strategy factory | `ai.koog.agents.ext.agent.reActStrategy(reasoningInterval: Int, name: String)` |
| Executor factory | `ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor(key) → SingleLLMPromptExecutor` |
| Agent | `AIAgent(promptExecutor, llmModel, strategy, toolRegistry, systemPrompt)` (Companion.invoke; `llmModel: LLModel`) |
| ToolSet registration | `ToolRegistry { tools(toolset) }` via `ai.koog.agents.core.tools.reflect.tools` |
| Models | `AnthropicModels.{Sonnet_4_5, Opus_4, Opus_4_1, Haiku_3_5, ...}` |

**Koog 0.5.1 reflection limitation**: methods with `Map<String, String>` parameters are rejected by the reflect-based `tools(toolset)` registration. We dropped the unused `args` parameter from `EmulatorToolset.executeAction` (Task 4.0) — it was never forwarded to `SessionActionController` anyway.

**Defaults at session boot**: advisor = `Opus_4`, executor = `Sonnet_4_5`. Both bumpable per-instance.

KoogToolToMcpSchema (reflection-based MCP schema generator) was not implemented — `McpServer.kt` retains hand-written tool schemas for parity with the existing FF1 system prompt. It's a future cleanup, not blocking the agent.

## 4. `EmulatorToolset` (shared)

Single class. Constructor takes already-wired collaborators:

```kotlin
class EmulatorToolset(
    private val session: EmulatorSession,
    private val controller: ApiController,
    private val actions: ActionRegistry,
) : ToolSet {
    @Tool @LLMDescription("Load a NES ROM by absolute path.")
    suspend fun loadRom(path: String): StatusResult

    @Tool @LLMDescription("Advance N frames while holding given buttons. …")
    suspend fun step(buttons: List<String>, frames: Int = 1, screenshot: Boolean = false): StepResult

    @Tool @LLMDescription("Press a button N times. …")
    suspend fun tap(button: String, count: Int = 1, pressFrames: Int = 5, gapFrames: Int = 15, screenshot: Boolean = false): StepResult

    @Tool @LLMDescription("Execute multiple {buttons,frames} entries in one call.")
    suspend fun sequence(steps: List<StepEntry>, screenshot: Boolean = false): StepResult

    @Tool @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    suspend fun getState(): StateSnapshot

    @Tool @LLMDescription("PNG screenshot of the current frame.")
    suspend fun getScreen(): ScreenPng

    @Tool @LLMDescription("Apply a RAM-watching profile (e.g. \"ff1\").")
    suspend fun applyProfile(id: String): StatusResult

    @Tool @LLMDescription("Reset the emulator.")
    suspend fun reset(): StatusResult

    @Tool @LLMDescription("List actions available for the active profile.")
    suspend fun listActions(): List<ActionDescriptor>

    @Tool @LLMDescription("Execute a registered game action by id.")
    suspend fun executeAction(profileId: String, actionId: String, args: Map<String, String> = emptyMap()): ActionResult

    @Tool @LLMDescription("Press / release / list profiles.")
    suspend fun press(buttons: List<String>): StatusResult
    @Tool @LLMDescription("…") suspend fun release(buttons: List<String>): StatusResult
    @Tool @LLMDescription("…") suspend fun listProfiles(): List<ProfileSummary>
}
```

Names, parameter shapes, and descriptions intentionally mirror today’s MCP and REST surfaces so the existing `docs/ff1-system-prompt.md` works unchanged.

`KoogToolToMcpSchema` is a small reflection helper that walks `@Tool`-annotated methods and produces MCP `ToolSchema` definitions, so `McpServer` registers tools without re-typing every schema.

## 5. Koog topology

Canonical Advisor pattern as supported natively by Koog (`createAgentTool`):

```
ExecutorAgent (Sonnet)         AdvisorAgent (Opus)
  ├─ reActStrategy(maxIters)     ├─ single-shot
  ├─ ToolRegistry:               ├─ ToolRegistry: getState, getScreen (read-only)
  │    EmulatorToolset (full)    └─ Returns: plan text
  │    advisor.createAgentTool(
  │      name = "askAdvisor",
  │      description = "Consult the planner when stuck or at a phase boundary",
  │      input = reason: String)
  └─ system prompt: ff1 prompt + “Call askAdvisor when uncertain.”
```

- **ExecutorAgent** drives the moment-to-moment ReAct loop. Receives current plan + recent observation in the user message; returns final outcome string when its `reActStrategy` terminates (or on `escalate` tool call — see §7).
- **AdvisorAgent** is invoked (a) once at session start to produce the initial plan, (b) on demand by the executor via `askAdvisor`, (c) on watchdog escalation by the runtime injecting a fresh user turn.
- **Provider**: `simpleAnthropicExecutor(System.getenv("ANTHROPIC_API_KEY"))`. Models: `AnthropicModels.Sonnet_4_*` (executor default), `AnthropicModels.Opus_4_*` (advisor). Exact constants picked at impl time against the Koog version pinned.

## 6. Perception layer

Goal: keep executor turns cheap by default, give it a screenshot only when the visual context actually changed.

- `RamObserver` reads `getState()` results through the active FF1 profile and exposes a typed `FfPhase` (`TitleScreen | NameEntry | Overworld | Dungeon(name) | Battle(enemyId, hpVec) | Dialog | GameOver`) plus diff vectors (HP, position, gold, battle-cursor).
- `ScreenshotPolicy` gates `step/tap/sequence(screenshot=true)` based on:
  - phase change since last turn,
  - executor explicitly requests it (`screenshot=true` in tool args),
  - first turn after `reset` / `applyProfile`,
  - watchdog suspicion (no RAM movement N turns running).
  Otherwise default `screenshot=false`. This is enforced by the runtime wrapping the toolset, not by the model — keeps cost predictable.
- Screenshots are passed to the next LLM turn via Koog’s native `image(path)` in the user message. PNGs are written to a per-session tmp dir to avoid stuffing base64 into the prompt manually.

## 7. Escalation triggers

Deterministic, runtime-side (not relying on the executor self-reporting):

| Trigger | Source | Action |
|---|---|---|
| Phase boundary (RAM-detected) | `RamObserver.diff` | Inject advisor consultation before next executor turn |
| No RAM progress for N=20 executor turns | watchdog | Inject advisor with reason="stuck" |
| Battle started | `RamObserver` `Battle` phase | Inject advisor (combat plan) |
| Battle ended, party alive | `RamObserver` exit `Battle` | Continue without advisor |
| `escalate(reason)` tool call from executor | Koog tool | Forward to advisor immediately |
| Step budget exceeded (default: 2000 tool calls) | runtime | Terminate with `OutOfBudget` |
| Token budget exceeded (default: configurable USD cap, conservative) | Koog usage callback | Terminate with `OutOfBudget` |

The watchdog runs as outer code around `executorAgent.run(...)`; when triggered, it stops the current executor invocation, calls the advisor with a fresh observation, then restarts the executor with the new plan.

## 8. Success / failure detection

Pure RAM-driven, evaluated each frame boundary by the runtime (not the LLM):

- `Outcome.Victory` ⇐ `phase == Battle && battle.enemy_id == GARLAND_ID && battle.enemy_hp[0] == 0` (one frame). Confirmed by waiting for `phase != Battle` and party HP > 0.
- `Outcome.PartyDefeated` ⇐ all party HP = 0 OR phase == GameOver.
- `Outcome.OutOfBudget` ⇐ §7 budget triggers.
- `Outcome.Error` ⇐ tool exception bubbled past Koog.

Garland’s enemy id and the FF1 RAM profile fields used must be confirmed against the existing `ff1` profile in the codebase during implementation; if missing, extending the profile is part of the plan.

## 9. CLI / runtime

```
./gradlew :knes-agent:run --args="--rom=roms/ff1.nes --profile=ff1 [--max-steps=2000] [--cost-cap-usd=5]"
```

`Main.kt` wires:

```kotlin
val session   = EmulatorSession.create(headless = false)
val controller = ApiController()
val toolset    = EmulatorToolset(session, controller, ActionRegistry.default())
val advisor    = AdvisorAgent(toolset, AnthropicModels.Opus_4_*)
val executor   = ExecutorAgent(toolset, advisor, AnthropicModels.Sonnet_4_*)
val agentSession = AgentSession(toolset, RamObserver(toolset), executor, advisor, budget)
val outcome = agentSession.run(goal = Goal.DefeatGarland)
```

Headed by default (Compose UI window so the developer watches gameplay live). `--headless` flag for CI / unattended runs.

## 10. Observability

Every executor / advisor turn appends one JSONL record to `runs/<timestamp>/trace.jsonl`:

```
{ "t": 123, "role": "executor", "phase": "Battle", "model": "sonnet-4-…",
  "input_tokens": 4123, "output_tokens": 312, "tool_calls": [...], "ram_diff": {...},
  "screenshot": "frame_00123.png" }
```

`runs/<timestamp>/summary.md` is written on termination: outcome, total cost (USD), turn count, escalation count, advisor invocation count.

## 11. Out of scope for V1 (deferred)

- Save / load state (would speed up dev loop dramatically; tracked as separate spec). V1 always runs from boot.
- Multiple goals beyond Garland.
- Online cost reporting / pause-on-budget UI.
- Replay-from-trace harness.
- Non-Anthropic models.
- Headless ROM-less acceptance test (would require a fixture profile).

## 12. Risks and open questions

- **FF1 RAM profile coverage.** We assume the existing `ff1` profile already exposes enemy HP and current phase; if not, profile extension is the first plan task.
- **Koog version stability.** Pin a specific Koog release; the multi-agent + reActStrategy APIs we rely on are documented but young. Plan must include a smoke test exercising both before deeper work.
- **Cost.** A from-boot Garland run is plausibly 50-200 executor turns + ~5-15 advisor calls. Order of magnitude: $1-5 with Sonnet+Opus mix and screenshots gated by phase change. Budget cap in §7 is the hard stop.
- **Determinism.** The agent isn’t deterministic; acceptance is a single observed success run, not a reproducible test. A future spec can address replay-based regression testing.
- **MCP server refactor regressions.** Removing the REST hop from MCP is a behavioral change for any current MCP user. We keep the REST-bridge mode behind a flag (`--remote`) so external MCP setups using a separate kNES process continue to work.

## 13. Acceptance test

Manual, recorded:

1. Fresh checkout, `ANTHROPIC_API_KEY` exported, `roms/ff1.nes` present.
2. `./gradlew :knes-agent:run`.
3. Compose UI window opens, agent plays.
4. Run terminates with `Outcome.Victory`, trace + summary written, total USD cost printed and ≤ configured cap.
5. Existing Claude Code MCP flow still works against the modified `knes-mcp` (sanity test against the FF1 system prompt, default in-process mode).
