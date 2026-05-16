# v2 Namespace Cleanup Design

**Date:** 2026-05-16  
**Branch:** ff1-v2-namespace-cleanup  
**Scope:** Mechanical package rename — no logic changes.

## Goal

The v2 agent is now the canonical agent. The `knes.agent.v2.*` sub-namespace was a scaffolding artifact from when v1 was still active. This cleanup promotes all v2 code to the top-level `knes.agent.*` namespace and drops the `V2` prefix from three class names.

## What Does NOT Change

- `knes.agent.skills.*` — v1 skill library (WalkOverworldTo, ExitInterior, RestAtInn, EquipWeapon)
- `knes.agent.perception.*` — shared perception stack
- `knes.agent.pathfinding.*` — shared pathfinding
- `knes.agent.runtime.LandmarkContext`, `StrategyContext`, `ToolCallLog` — v1 runtime utilities still used by skills and v2 agents alike

## Package Mapping

| From | To |
|------|----|
| `knes.agent.v2` | `knes.agent` |
| `knes.agent.v2.agents` | `knes.agent.agents` |
| `knes.agent.v2.llm` | `knes.agent.llm` |
| `knes.agent.v2.runtime` | `knes.agent.runtime` |
| `knes.agent.v2.tools` | `knes.agent.tools` |

The existing `knes.agent.runtime` package gets new neighbours from the promoted v2 runtime files. No name conflicts exist between the v1 runtime classes and the v2 runtime classes.

## File Renames (source)

| Old path (under `knes-agent/src/main/kotlin/knes/agent/`) | New path | Class rename |
|------------------------------------------------------------|----------|--------------|
| `v2/Main.kt` | `Main.kt` | — |
| `v2/V2Config.kt` | `Config.kt` | `V2Config` → `Config` |
| `v2/agents/AdvisorAgent.kt` | `agents/AdvisorAgent.kt` | — |
| `v2/agents/CartographerAgent.kt` | `agents/CartographerAgent.kt` | — |
| `v2/agents/ExecutorAgent.kt` | `agents/ExecutorAgent.kt` | — |
| `v2/agents/ReviewerAgent.kt` | `agents/ReviewerAgent.kt` | — |
| `v2/llm/AnthropicHttp.kt` | `llm/AnthropicHttp.kt` | — |
| `v2/llm/AnthropicSession.kt` | `llm/AnthropicSession.kt` | — |
| `v2/llm/GeminiPro31Client.kt` | `llm/GeminiPro31Client.kt` | — |
| `v2/llm/HaikuClient.kt` | `llm/HaikuClient.kt` | — |
| `v2/llm/SonnetClient.kt` | `llm/SonnetClient.kt` | — |
| `v2/runtime/Campaign.kt` | `runtime/Campaign.kt` | — |
| `v2/runtime/Log.kt` | `runtime/Log.kt` | — |
| `v2/runtime/MilestonePredicates.kt` | `runtime/MilestonePredicates.kt` | — |
| `v2/runtime/Phase.kt` | `runtime/Phase.kt` | — |
| `v2/runtime/Plan.kt` | `runtime/Plan.kt` | — |
| `v2/runtime/Resumer.kt` | `runtime/Resumer.kt` | — |
| `v2/runtime/SnapshotDumper.kt` | `runtime/SnapshotDumper.kt` | — |
| `v2/runtime/TurnLog.kt` | `runtime/TurnLog.kt` | — |
| `v2/runtime/V2Memory.kt` | `runtime/Memory.kt` | `V2Memory` → `Memory` |
| `v2/runtime/V2RunDirectory.kt` | `runtime/RunDirectory.kt` | `V2RunDirectory` → `RunDirectory` |
| `v2/runtime/Watchdog.kt` | `runtime/Watchdog.kt` | — |
| `v2/tools/MenuWalker.kt` | `tools/MenuWalker.kt` | — |
| `v2/tools/ToolSurface.kt` | `tools/ToolSurface.kt` | — |

## File Renames (tests)

| Old path (under `knes-agent/src/test/kotlin/knes/agent/`) | New path | Class rename |
|-----------------------------------------------------------|----------|--------------|
| `v2/llm/HaikuClientParseTest.kt` | `llm/HaikuClientParseTest.kt` | — |
| `v2/runtime/PhaseTest.kt` | `runtime/PhaseTest.kt` | — |
| `v2/runtime/V2MemoryTest.kt` | `runtime/MemoryTest.kt` | `V2MemoryTest` → `MemoryTest` |
| `v2/runtime/WatchdogTest.kt` | `runtime/WatchdogTest.kt` | — |
| `v2/tools/MenuWalkerTest.kt` | `tools/MenuWalkerTest.kt` | — |

## Build Configuration Change

`knes-agent/build.gradle`:
- Delete the `runV2` task — its `mainClass = 'knes.agent.v2.MainKt'` becomes identical to the `application { mainClass = 'knes.agent.MainKt' }` default after the rename. The standard `run` task replaces it.
- The `application { mainClass = 'knes.agent.MainKt' }` block is already correct; no change needed there.

## Execution Plan (single atomic commit)

1. Create new directories: `agents/`, `llm/`, `tools/` under both `src/main` and `src/test`
2. For each file: rewrite `package` declaration, strip `v2.` from all `import knes.agent.v2.*` lines, rename class where applicable, write to new path, delete old file
3. Update `build.gradle`: delete `runV2` task
4. Delete the now-empty `v2/` directory tree
5. Run `./gradlew compileKotlin` — must be zero errors before committing
6. Commit with message: `chore: promote v2 to knes.agent, drop V2 class prefixes`

## Invariants (must hold after)

- `./gradlew compileKotlin` passes
- `./gradlew test` introduces no new failures (pre-existing failures in live/integration tests that require a running emulator are acceptable)
- `./gradlew run` launches the agent (previously `runV2`)
- No file under `knes-agent/src/` contains `knes.agent.v2` in its package or import declarations
- The `v2/` directory no longer exists
