# Coneria End-to-End Pipeline — Design

**Date:** 2026-05-09
**Branch:** `ff1-buy-and-equip-coneria`
**Status:** approved (brainstorming → writing-plans)

## Goal

Wire a reliable end-to-end pipeline from Coneria-town entry to first-random-encounter-won, in one boot phase. Today buy works (4/4); exit and grind do not.

**Success = `screenState=0x68` (battle screen) fires after exit, `battleFightAll` wins, party returns to overworld with `mapflags.bit0=0`.**

## Pipeline (4 phases)

```
WALK_TO_SHOP → BUY → EXIT → GRIND
   (exists)  (exists) (rework) (rework)
```

Phases 3 (EXIT) and 4 (GRIND) are reworked. Phases 1–2 stay as-is.

### Phase 1 — WALK_TO_SHOP (existing)

In `runOutfitBootPhase`. Walks from world entry to shopkeeper. `AgentScratchpad` records the cardinals so Phase 3 can use the reversed trajectory as `historyHint`. When `KNES_FF1_LOAD_SAVESTATE` is set, this phase is skipped — savestate already places the party in-shop.

### Phase 2 — BUY (existing)

`BuyAtShop.invokeWithAdvisor` (cap=120). Achieves 4/4 weapon purchase via Gemini-Pro vision advisor. Ends at `boot_outfit_summary`; the post-buy savestate is dumped to `/tmp/spec5-post-buy.savestate` for fast iteration.

### Phase 3 — EXIT (rework)

Replace the brittle empirical/tree-detour stack with vision-LLM only.

**Sequence:**
1. B-spam ×8 to dismiss any lingering BUY/SELL/EXIT dialog (already in code).
2. `WalkInteriorVision(navigator, historyHint=scratchpad.renderForLLM(), maxSteps=60)`.
3. Success when `mapflags.bit0=0` (canonical "on overworld" per Disch). Post-success: 120-frame settle so any `mapflags.bit1` mid-scroll transient clears before Phase 4 reads world coordinates.
4. Failure → bail; `UnknownMapTrap` safety valve fires in strategic loop.

**`ExitTownEmpirical` and `ExitInterior.walkOutOfTownOverlay` stay in the codebase** but are **dropped from the hot path**. They remain reachable only when `outfitNavigator == null` (offline/test runs). No tree-detour, no hardcoded `(14,22)` approach, no `mf3-recovery` ladder.

**Prompt change in `VisionInteriorNavigator.SYSTEM_PROMPT`** — append one paragraph:

> When you've just bought weapons in a TOWN shop and need to leave: first walk SOUTH out of the shop building (the shopkeeper counter is north, the door is south). Then keep walking SOUTH along the dirt path between buildings until the camera scrolls off the town onto the overworld (grass, trees, mountains visible). LEFT/RIGHT during town-exit usually leads INTO another building's doorway — avoid unless SOUTH is genuinely blocked. Trees in the town overlay block movement; trees on the overworld do NOT — they're walkable encounter terrain, not obstacles.

This encodes user-hint #1 (DOWN-from-shop) and primes the model for the town/overworld tree-walkability distinction (hints #2/#3) so the model doesn't return STUCK when it sees trees on the south horizon.

### Phase 4 — GRIND (rework)

**Anchor placement (new):** post-exit, `runOutfitBootPhase` reads `worldX/worldY` after the 120-frame settle, classifies the 5×5 tile area around party using `OverworldMap.fromRom` (already constructed in `Main.kt`), and picks an anchor:

- If party stands on `forest` or `grass` → `anchor = (worldX, worldY)`.
- Else → `anchor = nearest forest/grass tile in 5×5`, preferring south + east (FF1 Coneria peninsula has open grass to the SE).
- Else (no walkable encounter tile in 5×5) → log warning, fall back to `anchor = (worldX, worldY)` and let `GrindLoop` report `WanderedOff` if needed.

**`GrindLoop` invocation:** `corridorRadius=3, maxStepsWithoutEncounter=12`.

**Encounter-rate diagnostics + retry:**
1. Per-step log already dumps `encounterCounter`; add `delta = encounterCounterPost - encounterCounterPre`. If delta is zero across all 12 steps, log a single warning (`grind_encounter_byte_dead`) but continue — `screenState=0x68` is the authoritative battle signal regardless of the counter byte.
2. If `GrindLoop` returns `NoEncounter`, re-anchor to the next forest/grass tile in the 5×5 area and retry. Max 2 re-anchors.

**No corridor-shift logic** (the cont-3 +2E/-4N shift moved the corridor into castle territory and is removed in this design).

## User-hint encoding

| Hint | Where encoded |
|---|---|
| #1 "Po wyjściu ze sklepu — DOWN" | `VisionInteriorNavigator.SYSTEM_PROMPT` (new paragraph above) |
| #2 "Zielone = grass = cel grindu" | Phase 4 anchor selection via `OverworldMap` tile classification |
| #3 "Drzewa na overworld są chodzące" | Same SYSTEM_PROMPT paragraph + `GrindLoop` treats `forest` as encounter zone |

## Phase boundaries / state contract

- Phase 3 fails → no Phase 4. `boot_phase3_exit_result {ok:false, ...}` written to trace; `runOutfitBootPhase` returns; strategic loop takes over.
- Phase 4 fails (`NoEncounter` after 2 re-anchors, `WanderedOff`, `Blocked`) → `boot_phase4_grind_result {ok:false, ...}`; `runOutfitBootPhase` returns; strategic loop takes over.
- Phase 4 success (`EncounteredBattle`) → `runOutfitBootPhase` returns; the existing `PostBattle` handler in `ExecutorAgent` fires `battleFightAll`. Pipeline END marker `boot_pipeline_end {victory:true}` is written when `battleFightAll` returns and `mapflags.bit0=0`.

## Trace markers (added)

- `boot_phase3_exit_result {ok, finalWorldX, finalWorldY, mapflags, steps, via:"vision"}`
- `boot_phase4_grind_anchor {anchorX, anchorY, tileClass}`
- `boot_phase4_grind_result {outcome, anchorReanchorCount, encounterByteDead}`
- `boot_pipeline_end {victory, lastPhase}`

## Out of scope

- EquipWeapon (bare-hand grind works; separate follow-up).
- Generalization to Pravoka / Elfheim / other towns. Once the mechanism works for Coneria, the prompt paragraph + `OverworldMap`-based anchor selection should generalize without code changes; not validated in this spec.
- PPU nametable tile-classification (Approach B — explicitly rejected; conflicts with `feedback_vision_primary.md`).
- Loop back to town for re-buy / re-grind cycles.
- `UnknownMapTrap` detector refactor (separate concern; current 10-obs threshold is acceptable safety valve).

## Files affected

- `knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt` — SYSTEM_PROMPT addition.
- `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` — `runOutfitBootPhase` Phase 3 (drop empirical/tree-detour from hot path) and Phase 4 (anchor selection via `OverworldMap`).
- `knes-agent/src/main/kotlin/knes/agent/skills/GrindLoop.kt` — encounter-byte delta log; no behavior change otherwise.
- New trace events written from `runOutfitBootPhase`.

## Risks

1. Vision-LLM still fails to exit Coneria reliably even with hint paragraph — mitigation: scratchpad `historyHint` is already wired and gives the model the walk-in trajectory; 60 steps is twice cont-3's failed run. If this still fails, fallback escalation is documented in HANDOFF as Approach B (PPU nametable) — separate spec.
2. `OverworldMap` tile classification doesn't expose `forest`/`grass` distinctly — mitigation: any tile that's neither `mountain` nor `water` nor a warp counts as walkable encounter terrain. Validation: read `OverworldMap.fromRom` API at plan time; if API insufficient, plan adds a thin classifier.
3. Encounter rate near Coneria peninsula is genuinely low — mitigation: re-anchor logic + 2 retries widen the search; if still zero encounters across all anchors, the trace makes this visible (`grind_encounter_byte_dead`) and the next session can move corridor further from coast.
