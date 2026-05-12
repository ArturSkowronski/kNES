# knes-agent v2 — Smoke 1 progress handoff

**Date:** 2026-05-12
**Branch state:** master at `51bbd93` (PR #127 squash-merged)
**Spec:** `docs/superpowers/specs/2026-05-12-knes-agent-v2-design.md`
**Plan:** `docs/superpowers/plans/2026-05-12-knes-agent-v2.md`
**Prior handoffs:**
- `2026-05-12-v2-smoke-0-handoff.md` (Smoke 0 wiring pass, E2-E5 deferred)
- `2026-05-12-v2-smoke-1-handoff.md` (Smoke 1 v1 — Town fix verified, 3 gaps identified)

## What landed this session

Two PRs merged to master:

| PR | Squash sha | Title | Commits |
|---|---|---|---|
| #126 | `3cf125c` | knes-agent v2: PDF architecture + Smoke 0 fixes | 26 |
| #127 | `51bbd93` | knes-agent v2 Smoke 1 progress — Town navigation, vision-driven Executor, first battle won | 14 |

### Subsystems delivered (PR #127 detail)

**Phase classifier** — 3 commits
- `Phase.Town` for `mapId=0 + mapflags.bit0=1` (was misclassified as Indoors → walkTo dispatched `exitInterior` bouncing party out).
- Battle detection: started with `screenState=0x68 || battleTurn>0 || enemyCount>0`, narrowed to `screenState=0x68` only after Smoke 1 v7 showed stale `battleTurn`/`enemyCount` causing sticky-Battle phase post-victory.

**Tool surface — stealth-no-op gates** — 4 commits
- `battleFightAll` Phase.Battle gate (was returning ok=true with 0 rounds outside battle, Sonnet looped 1484× on it in v3).
- `useMenu` RAM-diff gate (screenState/menuCursor/menuHandX/menuHandY pre/post; Reject if unchanged).
- ExecutorAgent plan-exhausted Reject + askLlm fallback fix (Reject vs plan-tail trade-off).
- ExecutorAgent `recentOutcomes` reset on plan change (was preventing fresh plans from ever executing step 0).

**Town navigation** — 3 commits
- mapflags=2 transition settle wait after walkTo Ok (60-frame poll up to ~5s wallclock).
- Vision-driven Town walkTo: Haiku per-step direction picker + RAM `smPlayerX/Y` verification.
- Perpendicular-cardinal fallback + stuck threshold 4→8 (escape corners Haiku can't unblock with one direction).
- Transition guard: abort townWalk if `mapId` or `mapflags.bit0` changes mid-loop (prevents accidental castle entry via north exit door).

**Executor vision (Option A — Haiku scene → Sonnet decide)** — 2 commits
- `AnthropicHttp` minimal Messages API wrapper (mirrors GeminiPro31Client).
- `HaikuClient.describeScene(b64)` → terse digest (`party: vp(8,7) / overlay: shop-menu / sprite: shopkeeper vp(8,6) [weapon] / ...`).
- `HaikuClient.directionTo(b64, targetText)` → N/S/E/W cardinal picker.
- `SonnetClient.decideTool(system, user)` → JSON tool decision.
- ExecutorAgent.askLlm now uses both instead of placeholder plan-tail.

**Campaign infrastructure** — 1 commit
- Milestone seeding (`boot → enter_coneria → buy_weapons → equip_weapons → exit_coneria → grind`) + per-turn advance based on phase + RAM signals. Was: `campaign.milestones=[]` forever, `currentMilestone()` fell through to "boot" fallback, Advisor kept replanning "Restart with default party."

**buyAtShop pre-flight** — 1 commit
- Reject early when `phase != Town` with hint "Walk into town first via walkTo(...)". Was: 17 calls in v12 from Overworld each returning downstream `"NotInShop: mapflags.bit0=0, mapId=0"`.

## Empirical trajectory (13 Smoke 1 runs)

| run | turns | Phase=Town | Phase=Battle | Castle trap | battleFightAll ok | Weapons bought |
|---|---|---|---|---|---|---|
| Smoke 1 (handoff) | 1500 | 1486 | 0 (misclassified) | no | 0 (1484 false-ok) | 0 |
| v2 (Gap #1) | 109 (killed) | 16 | 0 | no | 0 (gated correctly) | 0 |
| v3 (milestone) | 300 | 28 | 0 | no | 5 (rejected) | 0 |
| v4 | 43 (killed) | 0 frozen | 0 | no | 0 | 0 |
| v5 | 86 (killed) | 7 | 0 | no | 0 | 0 |
| v6 (mapflags fix) | 91 (killed) | 11 | 12 (sticky) | no | 0 | 0 |
| v7 (Phase Battle) | 89 (killed) | 1 brief | 11+ correct | no | **1** | 0 |
| v8 (narrow Battle) | 300 | 16 | 0 (no encounter) | no | 0 | 0 |
| v9 (vision Executor) | 64 (killed) | 29 | 0 | no | 0 | 0 |
| v10 (Town walkTo) | 52 (killed) | 44 | 0 | no | 0 | 0 (stuck at sm=(15,18)) |
| v11 | 22 (killed) | 3 | 0 | **15 turns** | 0 | 0 |
| v12 (transition guard) | 300 | 19 | 0 | **0 (guard fired 5×)** | 0 | 0 |

### Headline empirical wins
1. **v7 won the first random encounter** (+30 gold, party HP intact). The Phase=Battle classification fix unblocked it.
2. **v12 confirmed transition guard** keeps party out of castle (vs v11 had 15 turns trapped in throne room).
3. **Vision-driven Town walkTo moves party 15+ tiles** through Coneria via Haiku per-step cardinal picking (Smoke 1 v10 evidence: sm=(8,30) entry → sm=(15,18) interior).
4. **Milestone progression works as designed**: `boot done T2, enter_coneria done T3-T9, buy_weapons in_progress` — Advisor's plans no longer cycle "Restart with default party."

### Critical remaining gap
**0/4 weapons bought across all 13 smokes.** The vision-driven townWalk gets party near the shopkeeper but Haiku can't reliably land the final approach to keeper-adjacent (viewport distance ≤ 1). At sm=(15,18) in Coneria, the path NW to the weapon shopkeeper at local(11,10) hits walls/NPCs Haiku doesn't navigate around even with perpendicular fallback.

## Open follow-ups

### Highest leverage — Gap #3 keeper-approach (the actual unlock for buying)

`BuyAtShop.invoke` assumes party already faces the keeper. v1's `AgentSession.runOutfitBootPhase:1162-1244` runs:
1. post-enter screenshot scan via `HaikuConsult.scanInteriorCandidates`
2. walks party to `(keeper.screenX, keeper.screenY+1)` facing N
3. then invokes `BuyAtShop.invokeMany` with list-mode

v2 has all the primitives (`HaikuClient.scanInteriorCandidates` exists via Anthropic HTTP). What's missing: the **final-approach helper** that takes a viewport-relative sprite position and walks the party to keeper-adjacent + facing N. This is ~30-50 lines mirroring v1's logic.

Architectural options:
- **(a)** New ToolSurface tool `approachSpriteAndInteract(kind: String, action: "buy"|"talk"|"rest")` — composable, Advisor-plannable.
- **(b)** Embed in BuyAtShop's existing wrapper — couples buy-flow to keeper-approach.
- **(c)** Tighter `townWalkVision` target spec — accept "kind=shopkeeper" instead of (x,y) so Haiku stays focused on visual target through final approach.

Recommendation: **(a)** — keeps tools composable, makes inn/king/etc. interactions reuse the same primitive.

### Secondary

- **Reviewer audit** (spec §7) — still placeholder returning `{"actions":[]}`. Wire real Haiku call once buy flow works so audit has real state to review.
- **Resumer decision replay** (D2 TODO) — only matters when crashing mid-checkpoint.
- **Sonnet native tool-calling** (spec C3) — currently uses JSON-via-text. Koog upgrade.
- **Cartographer landmark discovery** (spec §6 paragraph 4) — currently relies on preseed; `targetedRepass` is stub.

## File references for next session

| concern | file | lines |
|---|---|---|
| Phase classifier | `knes-agent/src/main/kotlin/knes/agent/v2/runtime/Phase.kt` | 1-30 |
| Town walk loop | `knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt` | `townWalkVision` ~190-280 |
| Sonnet tool prompt | `knes-agent/src/main/kotlin/knes/agent/v2/agents/ExecutorAgent.kt` | `buildSonnetUserText` |
| Haiku scene/direction | `knes-agent/src/main/kotlin/knes/agent/v2/llm/HaikuClient.kt` | `describeScene` + `directionTo` |
| Milestone advance | `knes-agent/src/main/kotlin/knes/agent/v2/Main.kt` | `advanceMilestones` ~end |
| v1 keeper-approach reference | `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` | 1162-1244 |

## Reproducer

```bash
# All v2 tests:
./gradlew :knes-agent:test --tests 'knes.agent.v2.*'

# Smoke 0 (cheap, ~$0.05):
./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=10 --cart-seconds=20 --cart-vision-calls=2"

# Smoke 1 (300 turns, ~$0.50-1.50):
./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=300"

# Inspect latest run:
ls -la $(readlink ~/.knes/runs/latest-v2)
```

Required env: `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`. Optional: `GEMINI_MODEL` override.

## Lessons / principles for next session

- **Stealth no-ops are the dominant failure mode** in agent loops. Any tool that returns `ok=true` for a non-effective action will be picked repeatedly by Sonnet because it's the only path that doesn't tick the watchdog. Every tool added should have a precondition Reject path. See: battleFightAll, useMenu, buyAtShop.
- **Plan tail fallback must run real emulator frames.** A pure-Reject fallback freezes the game (Smoke 1 v4 evidence). The cure is to either run frames during Reject or fall back to a tool that taps.
- **recentOutcomes deque must reset on plan change.** Without this, fresh plans can never execute step 0 if the prior plan failed twice.
- **mapId vs mapflags semantics:**
  - Overworld: `mapId=0, mapflags=0`
  - Town overlay: `mapId=0, mapflags.bit0=1`
  - Transition transient: `mapflags.bit1=1` (dialog/menu active mid-transition — settle ~5s before next tool)
  - Building interior: `mapId>0`
  - Battle: `screenState=0x68` (only reliable signal; battleTurn/enemyCount retain stale post-victory)
- **Town navigation needs more than landmarks.** Knowing the shopkeeper is at local(11,10) ≠ being able to walk there. Vision-driven cardinal taps with perpendicular fallback get partway; final-approach (vp-relative walking) is its own primitive.
