# knes-agent v2 — Smoke 1 handoff (post-Town-fix)

**Date:** 2026-05-12
**Branch:** `ff1-buy-and-equip-coneria` HEAD `deb4694`
**Spec:** `docs/superpowers/specs/2026-05-12-knes-agent-v2-design.md`
**Plan:** `docs/superpowers/plans/2026-05-12-knes-agent-v2.md`
**Prior handoff:** `docs/superpowers/notes/2026-05-12-v2-smoke-0-handoff.md`

## What landed

Two of three Smoke-0 unblock items (per user scope decision: "just the 3 handoff items + smaller fix at T2/T3"):

1. `deb4694` — `fix(v2)`: Town vs Indoors phase split + landmarks digest in Advisor
   - `Phase.Town` for `mapId=0 + mapflags.bit0=1` (was: misclassified as `Indoors`).
   - `ToolSurface.walkTo`: `Phase.Town` → `Reject` (in-town pathfinder deferred). Eliminates the prior bounce-out where `Indoors→exitInterior` dispatch dumped party back to overworld.
   - `Watchdog`: `Town` threshold = 5.
   - `AdvisorAgent`: optional `LandmarkMemory` injection; renders `LandmarkContext` plus usage rules (overworld vs town-overlay vs interior) into the prompt.
   - `Main.kt`: passes preseeded `~/.knes/ff1-landmarks.json` (Coneria TOWN_ENTRY + weapon-shopkeeper) into Advisor.
   - `PhaseTest`: 4 cases (Town / Overworld / Indoors / Boot).

Deferred: (1) `DiscoverShop`/`DiscoverInn` wiring into Cartographer — preseeded landmarks cover Smoke 1 needs; (2) keeper-approach port from `AgentSession.runOutfitBootPhase` into `ToolSurface.buyAtShop`.

## Smoke 0 result (post-fix)

`runV2 --fresh --max-turns=10 --cart-seconds=20 --cart-vision-calls=2` — run dir `2026-05-12-1410-v2`.

| T  | Phase     | mapflags | wXY       | Tool        | Outcome | Note                                                       |
|----|-----------|----------|-----------|-------------|---------|------------------------------------------------------------|
| 1  | Boot      | 0        | (0,0)     | boot        | **ok**  | overworld after 28 taps                                    |
| 2  | Overworld | 0        | (146,158) | walkTo      | **ok**  | reached (147,155) in 3 steps (Coneria entry)               |
| 3  | Overworld | **2**    | (147,155) | buyAtShop   | fail    | mapflags.bit0=0 — transition transient (bit1=1, see below) |
| 4  | Overworld | 2        | (147,155) | buyAtShop   | fail    | same                                                       |
| 5–8 | Overworld | 2       | (147,155) | equipWeapon | fail    | WeaponNotInSlot (nothing bought yet)                       |

Pre-fix Smoke 0 (commit `589ad44`) had T3 dispatching `exitInterior` and bouncing party to overworld. Post-fix T2 reaches Coneria entry cleanly. **walkTo Reject path verified at Smoke 1 (T17 below).**

## Smoke 1 result

`runV2 --fresh --max-turns=1500` — run dir `2026-05-12-1415-v2` (completed in ~3 min).

Phase distribution:
```
Boot:      1 turn
Overworld: 13 turns
Town:      1486 turns  ← party stayed in Coneria town overlay (vs Smoke 0 baseline = always bounced out)
```

Tool/outcome tally:
```
battleFightAll   ok   1484
useMenu          ok       3
walkTo           ok       2
walkTo           reject   2  ← Town overlay walkTo correctly rejected
boot             ok       1
buyAtShop        fail     4
equipWeapon      fail     4
```

Outcome: **0/4 weapons bought, 0/4 equipped.** Spec §12 success criteria (≥3/4 buy + ≥3/4 equip + party back overworld) not met.

Key turns:
- T11-12: buyAtShop fails on overworld at Coneria entry while mapflags=2 (transition transient — bit1=1 = "screen transition / dialog active" per `ExitTownEmpirical.kt:104`).
- T13: `battleFightAll` returns ok in 0 rounds (no-op success — see Gap #1 below).
- T14: useMenu equip succeeds at opening field menu while on overworld.
- T15: party transitions to Town (mapflags=1, smPlayer=(16,23)).
- T17: walkTo(147,156) → Reject with new message "walkTo not implemented for Town overlay — use interactAt at landmark local coords". Phase fix verified at scale.
- T50, T500, T1500: stuck in `battleFightAll` no-op loop. Watchdog never trips because every call returns ok=true (resets counter via `skillProgress` branch).

## Gaps revealed (in priority order for next session)

### Gap #1 — `battleFightAll` stealth no-op (critical regression risk)
`toolset.executeAction(profileId="ff1", actionId="battle_fight_all")` returns `ok=true` in 0 rounds when there is no battle in progress. The skill must reject when `battleState=0` so the watchdog can fire stuck-signal. **This single bug killed the entire Smoke 1 campaign at T13.**

Fix sketch: in `DefaultToolSurface.battleFightAll`, gate on `Phase != Phase.Battle` → Reject.

### Gap #2 — mapflags=2 transition transient
After walking onto a TOWN_ENTRY tile, mapflags transiently equals 2 (bit0=0, bit1=1 = "dialog/menu active") before settling to mapflags=1 (in-town). v1's `ExitTownEmpirical` already handles this with a settle loop (`while ((mapflags & 0x02) != 0) wait; break`). The Advisor planned `buyAtShop` immediately after walkTo without a settle step.

Fix sketch: add a one-tick "settle" wait after walkTo in `ToolSurface.walkTo`, OR have Phase classifier treat mapflags=2 as a `Transition` phase that the Advisor's prompt instructs to be patient with.

### Gap #3 — Keeper-approach (deferred from Smoke 0 handoff, still open)
`BuyAtShop.invoke` assumes party already faces the shopkeeper. v1's `AgentSession.runOutfitBootPhase:1162-1244` runs a post-enter vision scan + walks party to (sx, sy+1) facing N before buying. v2's `ToolSurface.buyAtShop` does not. Smoke 1 confirms: even when party is in Town (T15+), buyAtShop would fail at "tap A on keeper" because party is at sm=(16,23) ≠ adjacent to keeper at local=(11,10) per preseeded landmark.

Fix sketch: option (X) from prior handoff — port the keeper-approach into `enterAndApproachShop` helper invoked by `ToolSurface.buyAtShop` when phase=Town.

### Gap #4 — Executor latching onto no-op tools
Sonnet picked `battleFightAll` 1484× because it was the only tool returning ok=true in Town. Fixing Gap #1 forces Executor to surface real failure; combined with Gap #3 the Executor will then actually drive the buy flow.

## Files committed this session

```
knes-agent/src/main/kotlin/knes/agent/v2/Main.kt                 (+1 -1)
knes-agent/src/main/kotlin/knes/agent/v2/agents/AdvisorAgent.kt  (+27 0)
knes-agent/src/main/kotlin/knes/agent/v2/runtime/Phase.kt        (+12 -1)
knes-agent/src/main/kotlin/knes/agent/v2/runtime/Watchdog.kt     (+1 -1)
knes-agent/src/main/kotlin/knes/agent/v2/tools/ToolSurface.kt    (+10 0)
knes-agent/src/test/kotlin/knes/agent/v2/runtime/PhaseTest.kt    (+30 new)
```

Total: 6 files, +80 / -3 lines, 1 commit.

## Resume instructions

```bash
# Confirm wiring still intact:
./gradlew :knes-agent:test --tests 'knes.agent.v2.*'

# Re-run Smoke 0 (cheap, ~$0.05):
./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=10 --cart-seconds=20 --cart-vision-calls=2"

# Inspect Smoke 1 archive:
ls /Users/askowronski/.knes/runs/2026-05-12-1415-v2/decisions/ | wc -l   # → 1500
```

Required env: `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`. Optional: `GEMINI_MODEL` override.

## Open follow-ups (carried from Smoke 0 handoff + new)

- **NEW:** `battleFightAll` must reject when not in battle (Gap #1) — single highest-leverage fix.
- **NEW:** mapflags=2 transition settle (Gap #2) — small, deterministic, no LLM cost.
- Keeper-approach in `ToolSurface.buyAtShop` (Gap #3) — half-day port from AgentSession.
- `EquipWeapon` MenuStuck — known v1-inherited bug.
- `Resumer` decision replay (D2 has a TODO).
- `ReviewerAgent.invokeHaiku` placeholder returning `{"actions":[]}`.
- `ExecutorAgent.askLlm` plan-tail fallback (no real Sonnet tool-calling yet).
- Cartographer `DiscoverShop`/`DiscoverInn` wiring (spec §6 paragraph 4) — only needed when preseeded landmarks aren't enough (other towns).
