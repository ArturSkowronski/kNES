# FF1 Grind & Heal Cycle — Design (Spec 1)

**Date:** 2026-05-06
**Branch target:** `ff1-grind-strategy` (TBD)
**Parent context:** Phase 2 agent reaches `Outcome.OutOfBudget` on Coneria peninsula traversal across 12 attempts. Bridge at world (157, 141) never crossed. Net consensus (FF1 NES guides): grind to `min(party_level) ≥ 3` near Coneria castle before attempting bridge, with periodic inn heals.

## 1. Goal & success criteria

**Goal:** Phase 2 agent achieves `min(char1..4_level) ≥ 3` via controlled grind near spawn (Coneria peninsula), with periodic returns to Coneria inn for heal, **before** attempting bridge crossing N (157, 141) toward Garland.

**Success criteria (measurable):**
1. **Strategy validation (longitudinal, not PR gate):** in ≥ 70% of Phase 2 runs from baseline `master`, RAM `char_level` reaches 3 for all 4 characters within `wallClockCapSeconds`. Measured across the manual validation runs (§6.3) and subsequent sessions; documented in handoff memory. PR merge gate is in §6.5 (test stability + functional integration test), not this 70%.
2. **Stability:** zero zombie loops in grind cycle; party HP never reaches 0 before decision point; no infinite N↔S oscillations.
3. **Integration:** existing `POSTBATTLE_DISMISS_CAP=150` and `UnknownMapTrap` detection continue to work in grind mode — no regression in v9-v11 iteration tests.
4. **Out of scope (Spec 2):** equipment shopping, bridge crossing, reaching Garland. Spec 1 ends at `min_level=3` with party near spawn.

**Non-goals:**
- Optimal XP/sec (we prefer stability).
- Strategies for non-default party classes (assume save-state default party).
- Combat with anything other than near-spawn random encounters (tile 0x00 grass, Coneria peninsula).

## 2. Architecture

Top-level state machine in `AgentSession` (extension of existing loop, not replacement):

```
                  ┌──────────────────┐
                  │  STRATEGIC       │
                  │  DECISION POINT  │ ← consult Advisor (RAM + screenshot + last 3 decisions)
                  │                  │   returns enum: GRIND | REST | BRIDGE
                  └────────┬─────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   ┌────────┐         ┌────────┐         ┌─────────┐
   │ GRIND  │         │  REST  │         │ BRIDGE  │
   │ skill  │         │ skill  │         │ (existing
   │        │         │        │         │ traversal)
   └───┬────┘         └───┬────┘         └────┬────┘
       │                  │                   │
       └──────────────────┴───────────────────┘
                          │
                          ▼
                  back to DECISION POINT
```

**Decision point invocations:**
- After every `BattleFightAll` (postbattle dismiss completed).
- After exiting inn (`restAtInn` returned).
- After PartyDefeated → bail session.
- **Never** mid-skill — skills are atomic and decide themselves when to return control.

**One-way switch:** `grindModeActive` flag is true at session start, becomes false once advisor returns `BRIDGE`. After flip, no return to grind mode (avoids bridge↔grind oscillation).

**Unchanged:**
- POSTBATTLE_DISMISS_CAP / UnknownMapTrap detection / fog system / landmark memory — all live in `AgentSession`, not skills.
- Existing Advisor prompt for traversal (V5.32 frontier bias etc.) — used only after `grindModeActive` flips false.

## 3. Components

### 3.1 `GrindLoop` skill

**Location:** `knes-agent/src/main/kotlin/knes/agent/skills/GrindLoop.kt`

**Interface:**
```kotlin
class GrindLoop : Skill {
    override val id = "grindLoop"
    override val description = "Walk N-S corridor near spawn to trigger random encounters"

    data class Args(
        val anchorX: Int = 157,
        val anchorY: Int = 158,
        val corridorRadius: Int = 3,
        val maxStepsWithoutEncounter: Int = 6
    )

    suspend fun execute(args: Args, ctx: SkillContext): SkillResult
}
```

**Outcomes:** `EncounteredBattle`, `NoEncounter`, `WanderedOff` (drifted > 2× corridorRadius from anchor — e.g. fell into warp), `Blocked` (fog block), `BudgetExhausted`.

**Mechanics:** uses existing `WalkOverworldTo` with target=`(anchorX, anchorY ± corridorRadius)`. Encounter detection = RAM `screenState ∈ {0x68, 0x63}`. On detection: returns immediately (BattleFightAll handled by AgentSession's PostBattle handler).

### 3.2 `RestAtInn` skill

**Location:** `knes-agent/src/main/kotlin/knes/agent/skills/RestAtInn.kt`

**Interface:**
```kotlin
class RestAtInn : Skill {
    override val id = "restAtInn"
    override val description = "Travel to Coneria inn, pay for rest, return to overworld"

    data class Args(
        val innEntryWorldTile: Pair<Int, Int>,
        val innInteriorMapId: Int
    )
}
```

**Outcomes:** `Rested` (all HP = max after exit), `InsufficientGold`, `InnNotFound`, `BudgetExhausted`.

**Mechanics:**
1. `walkOverworldTo(innEntryWorldTile)` → verify `currentMapId == innInteriorMapId` after entry.
2. `walkInteriorVision` to innkeeper tile (vision-based; interior layout).
3. Dialog: `tap A` until gold drops AND HP rises to max (RAM-driven exit, not frame count). Bail at 30 taps with `InnNotFound`.
4. `exitInterior` → verify `currentMapId == 0` (overworld).

**Open in plan:** `innInteriorMapId` and innkeeper tile coordinates require one ad-hoc explorer run to discover. Spec defaults them to TBD; plan includes "discover values" step.

### 3.3 `StrategicDecision` enum + advisor consult

**Location:** `knes-agent/src/main/kotlin/knes/agent/runtime/StrategicDecision.kt` + change in `AgentSession.kt`.

**Enum:**
```kotlin
enum class StrategicDecision { GRIND, REST, BRIDGE }
```

**Helper signature (in `AgentSession`):**
```kotlin
private suspend fun consultAdvisorForStrategy(
    ram: Map<String, Int>,
    screenshot: ByteArray?,
    recentDecisions: List<StrategicDecision>
): StrategicDecision
```

**Advisor prompt structure** (dedicated path, separate from existing traversal advisor):
- System: "You are a strategic FF1 advisor. The party is grinding XP near Coneria spawn. Decide ONE action."
- User: party stats (RAM-derived: `min_level`, `min_hp_pct`, `total_gold`, `worldX/Y`, `distance_to_inn`, `distance_to_bridge`, `recent_decisions=[...]`).
- Attached screenshot (overworld view).
- Output: single token `GRIND` | `REST` | `BRIDGE` (regex-parsed).

**Throttling:** advisor consult counted against `budget.maxAdvisorCalls`. Each decision point = 1 advisor call.

## 4. Data flow

Full cycle (PostBattle → next encounter):

```
1. BATTLE ENDS
   AgentSession PostBattle handler (existing) auto-dismisses via BattleFightAll.
   screenState transitions out of {0x68, 0x63}.
   consecutivePostBattle reset to 0.

2. STRATEGIC DECISION POINT (new gate)
   if grindModeActive:
       ram = observer.ramSnapshot()
       screenshot = toolset.getScreenshot()
       decision = consultAdvisorForStrategy(ram, screenshot, recentDecisions.takeLast(3))
       recentDecisions += decision
       trace.record(TraceEvent("strategy", decision.name, ram-summary))

       when (decision) {
           GRIND  -> invoke skill "grindLoop"
           REST   -> invoke skill "restAtInn"
           BRIDGE -> grindModeActive = false; fall through to existing advisor flow
       }
       continue
   else:
       // existing V5.34 advisor flow (frontier bias, walkOverworldTo to bridge, etc.)

3. SKILL EXECUTION (deterministic)
   GrindLoop / RestAtInn run their internal step sequence.
   Each step:
     - reads ramSnapshot
     - issues controller actions via existing toolset
     - checks early-exit conditions (encounter started, mapId changed unexpectedly)
   Returns SkillResult.

4. SKILL COMPLETION
   AgentSession resumes main loop:
     - if encounter started during skill → next iter hits PostBattle handler (back to step 1)
     - if NoEncounter / Rested / etc. → next iter hits decision point again (step 2)
     - if WanderedOff / InnNotFound → log, fall back to existing advisor flow (treat as exception)
```

**RAM snapshot fields used at decision point:**

| Field | Source | Use |
|---|---|---|
| `char1..4_level` | profile (level-1 stored) | min_level computation, exit criterion |
| `char1..4_hpLow/High` | profile | min_hp_pct |
| `goldLow/Mid/High` | profile | total_gold (24-bit LE) |
| `worldX/Y` | profile | position; distance_to_inn, distance_to_bridge |
| `currentMapId` | profile | sanity check (must be 0 = overworld at decision point) |
| `screenState` | profile | sanity (must NOT be in 0x68/0x63 — handled in step 1) |

**Recent decisions buffer:**
- In-memory `ArrayDeque<StrategicDecision>` on `AgentSession`, max size 4 (size 4 needed for anti-thrash check; advisor sees only last 3).
- Cleared at session start.
- Passed to advisor as text: last 3 entries, e.g. `"recent: [GRIND, GRIND, REST]"`. Anti-thrash uses internal full size-4 view.

**Sanity guards on advisor output:**
- If advisor returns `BRIDGE` but `min_level < 2`: log warn + override to `GRIND` (advisor sometimes hallucinates premature commit).
- After flip to `grindModeActive=false`, advisor returning anything but BRIDGE on subsequent calls is ignored (one-way switch).

## 5. Error handling

| Situation | Detection | Handling |
|---|---|---|
| **PartyDefeated during grind** | `FfPhase.PartyDefeated` (existing: `!anyAlive && partyCreated`) | Bail entire session with `Outcome.PartyDefeated`. No runtime auto-reset (Phase 2 has `FF1_SAVESTATE` — operator restarts). |
| **Zero gold on `REST`** | `restAtInn` returns `InsufficientGold` | Decision passed back to advisor via `recentDecisions` + hint in next prompt ("last REST failed: no gold"). Advisor should pick `GRIND` (gold drops from encounters). No inn → continue grind until PartyDefeated or level reached. |
| **`grindLoop` returns `WanderedOff`** | Skill detected drift > 2× corridorRadius from anchor | Log, fall back to existing advisor flow (one tick only). Next decision point resumes. |
| **`grindLoop` returns `Blocked`** (fog poisoning) | WalkOverworldTo returns BLOCKED | Same: fall back to existing advisor (V5.32 frontier bias handles). |
| **Oscillation GRIND↔REST** | `recentDecisions` shows strict alternating last 4 entries | Hard guard in `consultAdvisorForStrategy`: override next decision to GRIND. Log `[strategy-anti-thrash]`. |
| **Advisor returns garbage token** | Regex parse returns null | Default to `GRIND`. Log. (Conservative: GRIND continues progress, never ends suboptimally.) |
| **`restAtInn` infinite-tap loop** | Gold doesn't drop within N taps after entering dialog | Skill bails after 30 taps with `outcome=InnNotFound`. Mirrors `MAX_POST_BATTLE_TAPS` in `BattleFightAll`. |
| **Budget exhausted mid-skill** | Skill sees `wallClockCapSeconds` exceeded | Skill returns `BudgetExhausted`. AgentSession's top-of-loop check catches it next iter (existing V5.34 behavior). |
| **Encounter during `restAtInn` walkOverworldTo** | `screenState == 0x68` mid-skill | Skill returns immediately (BattleFightAll handled by AgentSession PostBattle). Next decision point: advisor sees we're back on overworld with full HP → re-picks REST if appropriate. |
| **`bridgeTraversal` phase, advisor regrets** | `grindModeActive=false`, advisor wants grind | Not allowed (one-way switch). Subsequent advisor outputs ignored if not BRIDGE. |

**Explicitly NOT handled in Spec 1:**
- Rare engine bugs (innkeeper crash). Manual debug if encountered.
- Multi-step healing (magic + inn). Inn only.
- Fog poisoning specific to grind corridor — relies on existing V5.33 mechanism.

## 6. Testing strategy

### 6.1 Unit tests (JUnit, deterministic)

Location: `knes-agent/src/test/kotlin/knes/agent/...`

| Test | Verifies |
|---|---|
| `GrindLoopExitConditionsTest` | Exit codes for 4 scenarios: encounter mid-walk → `EncounteredBattle`; 6 stepless walks → `NoEncounter`; drift outside corridor → `WanderedOff`; fog block → `Blocked`. Mock `Toolset` with scripted RAM snapshots. |
| `RestAtInnGoldCheckTest` | `InsufficientGold` when total_gold < cost (TBD); `Rested` when gold drops and HP rises to max; `InnNotFound` after 30 taps without gold change. |
| `StrategicDecisionParserTest` | Regex parses `GRIND` / `REST` / `BRIDGE`; garbage tokens → default `GRIND`; case-insensitive, whitespace tolerant. |
| `AntiThrashGuardTest` | `recentDecisions = [GRIND, REST, GRIND, REST]` → next decision forced to GRIND regardless of advisor output. |
| `OneWaySwitchTest` | After `BRIDGE`, advisor returning `GRIND` is ignored; `grindModeActive` stays false. |

Target: 5-7 unit tests. No real ROM integration.

### 6.2 Integration test (real ROM, savestate-driven)

One test: `GrindAndHealCycleE2ETest`.

- Loads FF1 ROM + savestate=spawn (Coneria area, party L1, gold=400, HP=full).
- Stub Advisor (deterministic): returns `GRIND` × 5, then `REST` × 1, then `GRIND` × 5, then `BRIDGE`.
- Runs `AgentSession` with `grindModeActive=true`, wallclock budget=120s.
- Asserts:
  - At least 1 PostBattle event (encounter triggered).
  - After `REST` decision: all character HP = max (RAM check).
  - After `BRIDGE`: `grindModeActive == false`, agent continues existing flow (test ends here, doesn't validate actual bridge crossing).
  - No `Outcome.PartyDefeated`.

Target: 1 e2e test. Stubbed advisor (no API calls in CI).

### 6.3 Manual validation runs (sanity, not automated)

Pre-merge:
- 3 Phase 2 runs with real Anthropic advisor on `ff1-grind-strategy` branch.
- Metrics in PR description:
  - Did `min_level` reach 3? (yes/no per run)
  - Strategic advisor call count per run (should not explode — sanity ~10-30).
  - Oscillation in `recentDecisions` log.
  - Any `Outcome.PartyDefeated`.
- Merge **not** blocked on "all 3 reach L3" — this is hypothesis validation from the net, not acceptance criterion. Result documented in handoff memory.

### 6.4 Out of scope

- Full traversal-to-Garland E2E (Spec 2 and beyond).
- Per-class balance (default party assumed).
- Budget stress tests (existing POSTBATTLE_DISMISS_CAP test covers).

### 6.5 Acceptance criterion (Spec 1 PR)

- All unit tests green.
- Integration test green.
- Master baseline test count not regressed (233 pass / 2 known fail / 7 skipped → equal or better).
- Manual runs documented in PR (regardless of outcome).

## 7. Decomposition context

This is **Spec 1 of 2** for the leave-peninsula initiative.

| Spec | Builds | Status |
|---|---|---|
| **Spec 1** (this doc) | `grindLoop` + `restAtInn` skills + AgentSession strategic decision point | **Now** |
| **Spec 2** | `buyAtShop` skill (Coneria weapon shop). Independent of Spec 1; logically pre-grind in gameplay (buy weapons → grind faster) but technically pluggable later. | After Spec 1 validates core hypothesis |

Reaching Garland requires both specs plus existing bridge traversal (already implemented, just unreachable today). Spec 2 may become unnecessary if Spec 1 validation shows L3 grind sufficient without weapon upgrades.

## 8. Open items (resolved in plan, not spec)

- `innInteriorMapId` and innkeeper tile — autonomously discovered (see §9).
- Inn cost — observed at first heal, persisted as part of landmark `note`.
- Coneria entry tile from spawn — already in landmark memory from Phase 1 explorer.

## 9. Revision: autonomous inn discovery (replaces manual probe)

Original §3.2 assumed a one-shot manual probe to capture `innInteriorMapId` + innkeeper coords. **Revised:** the agent must discover the inn itself — hardcoding a manual-probe value defeats the autonomous-play premise.

**New skill `DiscoverInn`** (sibling to `RestAtInn`, same shape — Skill interface, deterministic A-tapping, RAM validation):
- Pre-condition: party is inside a candidate building (caller has already walked to a building entry tile and crossed into a non-zero mapId).
- Behavior: tap A up to 30× watching RAM. If `gold` drops AND `min_hp%` reaches 100 → SUCCESS: persist `Landmark(kind=NPC_INNKEEPER, mapId=<current>, localX/Y=<current>, note="cost=$X")` to `LandmarkMemory` (using `recordIfNew`), return `Rested`.
- If 30 taps elapse without delta → return `WrongBuilding` (caller exits and tries next candidate).

**`LandmarkKind` extension:** add `NPC_INNKEEPER` (existing enum already has `NPC_KING`, `NPC_SHOPKEEPER`, `NPC_GENERIC`).

**AgentSession REST handler revision:**
1. Check `LandmarkMemory.findInnkeeper()`:
   - **Hit:** `walkOverworldTo(coneriaEntry)` + walk to landmark's `(localX, localY)` + `RestAtInn(innInteriorMapId=landmark.mapId)`. Fast path; uses existing skill.
   - **Miss:** advisor vision consult ("you see a town interior; list candidate inn-building tiles from this screen") → for each candidate: walk to entry, enter (mapId change), invoke `DiscoverInn`. On `Rested`, done. On `WrongBuilding`, exit and try next. Exhausted candidates → log + return to advisor (next REST decision retries).

This keeps `RestAtInn` (Task 6) intact as the cached-hit path. `DiscoverInn` adds discovery + persistence for first encounter. After the first successful discovery in any run, all subsequent REST calls — in this and future sessions — use the fast path because `LandmarkMemory` is persistent JSON on disk.

**Coneria entry coords:** assumed already in `LandmarkMemory` from Phase 1 explorer (`TOWN_ENTRY` kind). If absent, AgentSession falls back to existing advisor flow (out of scope for this spec).
