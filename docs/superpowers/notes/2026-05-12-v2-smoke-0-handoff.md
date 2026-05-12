# knes-agent v2 — Smoke 0 handoff

**Date:** 2026-05-12
**Branch:** `ff1-buy-and-equip-coneria`
**Spec:** `docs/superpowers/specs/2026-05-12-knes-agent-v2-design.md`
**Plan:** `docs/superpowers/plans/2026-05-12-knes-agent-v2.md`

## What landed

Plan phases A → D complete + plan phase E1 (Smoke 0). Commit range: `0981b9a` → `22ebf0f` (22 commits).

```
Phase A — Foundations         (A1-A9)  scaffold, run-dir, data classes, Memory, Phase, Watchdog, SnapshotDumper
Phase B — Tool surface        (B1-B4)  MenuWalker (+test), ToolSurface, macro wiring
Phase C — Agents              (C1-C5)  Gemini/Sonnet/Haiku clients, Advisor, Executor, Reviewer, Cartographer
Phase D — Orchestration       (D1-D4)  Main bootstrap, Resumer, campaign loop
Phase E — Validation          (E1)     Smoke 0: fresh boot passed
                              (E2-E5)  deferred — see "Why deferred" below
```

**Test surface (per spec §12 — minimal):**
- `V2MemoryTest` — write/reopen round-trip (1 test)
- `WatchdogTest` — phase thresholds, whitelist, RAM/progress reset (4 tests)
- `MenuWalkerTest` — path parsing + invalid input (5 tests)
- All green (`./gradlew :knes-agent:test --tests 'knes.agent.v2.*'`)

## Smoke 0 result

`./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=10 --cart-seconds=20 --cart-vision-calls=2"` — **wiring pass**.

| Turn | Tool | Outcome | Note |
|---|---|---|---|
| 1 | boot | **ok** | `Reached overworld after 27 taps (worldX=0x92, char1_hp=0x23)` |
| 2 | walkTo | fail | first attempt; Advisor plan target was overworld coord, party still in town overlay |
| 3 | walkTo | **ok** | recovered |
| 4 | interactAt | fail | retry |
| 5 | interactAt | **ok** | reached shop tile candidate |
| 6–7 | buyAtShop | fail | LandmarkMemory empty — no NPC_SHOPKEEPER landmark |
| 8–10 | equipWeapon | fail | known MenuStuck (spec §13) |

Persistence verified — `~/.knes/runs/2026-05-12-1304-v2/`:
- `campaign.json`: lastTurn=10, one PlanEntry from Advisor
- `current_plan.json`: 8 well-formed steps, cursor=3
- `decisions/turn-{00001..00010}.json`: per-turn observations + tool + outcome + watchdog state
- `snapshots/turn-{00000..00010}.png` + `cart-00001.png`: per-iter PNG dumps confirmed
- `latest-v2` symlink updated

## Fixes applied during Smoke 0 (committed)

1. **`runV2` working dir** (`066774a`) — Gradle JavaExec defaults working dir to the subproject. ROM at `roms/ff.nes` (relative to project root) failed to load. Set `workingDir = rootProject.projectDir`.
2. **Gemini model** (`066774a`) — spec named "Gemini 3.1 Pro" from the PDF; that ID doesn't exist on `generativelanguage.googleapis.com/v1beta` (404). Switched to `gemini-2.5-pro` (v1's proven model). Class name `GeminiPro31Client` kept; comment documents reality. Override via `GEMINI_MODEL` env.
3. **HTTP timeout** (`066774a`) — default ktor CIO times out before Gemini 2.5 Pro thinking-mode completes. Set `requestTimeoutMillis = 180_000`.
4. **`boot()` tool** (`22ebf0f`) — first Smoke 0 run had Advisor emit `useMenu(path="[\"NEW GAME\"]")`, hallucinating path grammar for title-screen / class-creation. ToolSurface didn't cover the title→party-creation flow. Added `boot()` wrapping `PressStartUntilOverworld`. Plumbed through Executor dispatch + Main wiring.
5. **Advisor prompt** (`22ebf0f`) — tightened to spell out every tool's exact arg keys and the full `useMenu` path grammar (`main/equip/charN/weapon/N`, no raw labels). Second Smoke 0 run obeyed.

## Plan deviations from the original spec

| Plan task | Deviation | Reason |
|---|---|---|
| A6/A7 | Bundled into A2 commit, not separate | Files already on disk untracked; `git add` swept them in |
| A7 | Off-by-one in Watchdog (initial `lastRamHash=0`) | Caught by WatchdogTest. Fix: nullable `lastRamHash`, observe counts from first call |
| B3 | `executeAction` result field is `.data`, plan said `.state` | API mismatch |
| C2/C4 | Plan's `substringAfter("{")…substringBeforeLast("}")` JSON extract is brittle | Replaced with `indexOf('{')` / `lastIndexOf('}')` — tolerates markdown fences |
| C5 | Plan's Cartographer used `dump(-turnCounter)` producing `turn--00001.png` | Added `dumpCartographer(iter)` → `cart-NNNNN.png` |
| D1+D3 | Merged | Plan had D1 ship `TODO("wire in D3")` that throws at runtime; trivial to do at once |
| D4 | `StateSnapshot.frame: Int` vs `TurnLog.frame: Long` | `state.frame.toLong()` |

## Why E2–E5 deferred

Smoke 1 (buy + equip + exit Coneria) needs:
- **LandmarkMemory populated** for Coneria's `NPC_SHOPKEEPER` entries.
- **In-town vs overworld navigation** — `walkTo` currently dispatches by Phase.fromRam, which classifies the post-boot Coneria spawn (mapId=0, mapflags=1) as `Indoors`. Advisor produces overworld coords as targets; exitInterior wanders.

The spec (§6 last paragraph) explicitly defers indoor landmark scanning to "lazy first-Executor-visit to each building" — that hook is **not yet wired**. Running Smoke 1 against the current build is guaranteed to fail at `buyAtShop` (line of evidence: turns 6–7 of Smoke 0 above) and burns Gemini quota without exercising new code.

Cost estimate to push through anyway: ~$1–2/run × likely 3+ runs while diagnosing = $5+ for negative signal we already have.

## Unblock conditions for Smoke 1+

Roughly the work below would land Smoke 1 (estimate: half-day):

1. **Wire `DiscoverShop` / `DiscoverInn` into ToolSurface as a `discoverInteriorLandmarks()` tool**, or add a "first-visit lazy scan" hook in `walkTo` indoor path (Spec §6 paragraph 4).
2. **Inject the Advisor with a "landmarks known" digest** so it stops emitting raw `walkTo(x,y)` when only a landmark name is known.
3. **Phase classifier nuance** — distinguish "Coneria town overlay" (mapId=0, mapflags=1) from "interior building" (mapId>0). `walkTo` for the former should still dispatch to overworld pathfinder, not `exitInterior`.

## Resume instructions

```bash
# Re-run Smoke 0 (cheap, proves wiring intact):
./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=10 --cart-seconds=20 --cart-vision-calls=2"

# All unit tests:
./gradlew :knes-agent:test --tests 'knes.agent.v2.*' --rerun-tasks

# Inspect newest run:
ls -la $(readlink ~/.knes/runs/latest-v2)
```

Required env: `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`. Optional: `GEMINI_MODEL` override.

## Open follow-ups (not blocking PR)

- `EquipWeapon` MenuStuck — known v1-inherited bug, scheduled advisor-rewrite per spec §13.
- `Resumer` decision replay (D2 has a TODO) — only matters when crashing mid-checkpoint.
- `ReviewerAgent.invokeHaiku` is a placeholder returning `{"actions":[]}` — wire real Anthropic call once Smoke 2 (grind) makes audit signal worth checking.
- `ExecutorAgent.askLlm` falls back to plan-tail; real Sonnet tool-calling deferred to follow-up (per plan §C3 inline TODO).
- Cartographer time-budget uses `>` not `>=` — 3s overrun. Cosmetic.
