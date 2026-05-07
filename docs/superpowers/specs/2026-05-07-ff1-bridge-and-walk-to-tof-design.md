# FF1 Bridge → Walk to Temple of Fiends — Design (Spec 3a)

**Date:** 2026-05-07
**Branch target:** TBD (cut from `ff1-buy-at-shop` HEAD after Spec 2 lands and Spec 2 cleanup ships)
**Parent context:** Spec 1 (`2026-05-06-ff1-grind-and-heal-cycle-design.md`) ships GRIND/REST/BRIDGE strategic decisions. When the advisor returns `BRIDGE`, the session today only flips `grindModeActive=false` and falls through to the LLM executor, which then must drive bridge crossing + overworld walk to the Temple of Fiends entrance via prose plans. That LLM-driven path is non-deterministic, expensive, and reproduces poorly. Spec 3a replaces it with a deterministic post-bridge phase analogous to Spec 1's GRIND state and Spec 2's outfit boot phase.

## 1. Goal & success criteria

**Goal:** When `BRIDGE` strategic decision fires, the session deterministically walks the party from the Coneria peninsula across the bridge at world (157, 141) to a tile **adjacent to the Temple of Fiends entrance**, then yields control without entering the temple. Encounters during transit are handled by the existing main-loop PostBattle auto-dismiss path.

**Success criteria (measurable, per session):**
1. **Functional:** after `BRIDGE` decision, in ≥ 2/3 manual validation runs the party RAM `worldX`/`worldY` settles at Manhattan distance ≤ 1 from a discovered `tofEntranceTile` within the wall-clock budget.
2. **Persistence (gameplay-derived only):** on first `Reached`, the discovered ToF entrance is persisted as a `TEMPLE_ENTRY` landmark in the existing `landmarkMemory` JSON. Subsequent sessions skip the `DiscoverChaosShrine` vision call and walk straight to the cached tile. **No savestate-hash-keyed flag, no separate state file.**
3. **Stability:** zero regressions in Spec 1 (35 tests) and Spec 2 (23 tests); no new wedge paths in the main loop.
4. **Resilience:** retry budget exhaustion (3× discover failures or 5× consecutive `WalkOverworldTo` no-move) bails cleanly to LLM executor (`bridgePhaseActive=false`, log `bridge_phase_summary: bailed_to_llm`); never wedges the session.

**Non-goals:**
- Entering the Temple of Fiends interior (Spec 3b).
- Coordinate-targeted interior nav (Spec 3b).
- Garland boss fight tactics (Spec 3c).
- Princess pickup, return to King Coneria, bridge unlock cutscene (Spec 3d).
- Optimal route through forced-encounter tiles. We trust existing fog/warp memory plus `WalkOverworldTo`'s BFS.

## 2. Architecture

Spec 3a adds a **third strategic state** alongside Grind/Rest. It plugs into the existing main loop at the same point where `runStrategicTick` flips `grindModeActive=false` for `BRIDGE` — but now also flips `bridgePhaseActive=true` (mutually exclusive with `grindModeActive`). On every Overworld tick while `bridgePhaseActive`, the loop invokes a deterministic walker; encounters fall through to existing PostBattle auto-dismiss; nothing else in the loop changes.

```
┌────────────────────────────────────────────────────────────────┐
│ AgentSession.run() main loop (existing)                        │
│                                                                │
│   if grindModeActive && Overworld → runStrategicTick()         │
│     GRIND  → GrindLoop                                         │
│     REST   → strategicPlan injection (LLM)                     │
│     BRIDGE → grindModeActive=false                             │
│              bridgePhaseActive=true   ← NEW                    │
│                                                                │
│   if bridgePhaseActive && Overworld → runBridgeTick()  ← NEW   │
│     ├─ if !tofEntranceTile known → DiscoverChaosShrine         │
│     ├─ if adjacent → persist TEMPLE_ENTRY, flip flag off, log  │
│     ├─ if stall budget exhausted → bail to LLM                 │
│     └─ else WalkOverworldTo(tofEntranceTile)                   │
│                                                                │
│   Battle/PostBattle → existing auto-dismiss + battle_fight_all │
└────────────────────────────────────────────────────────────────┘
```

**Why state-in-loop, not orchestrator (Spec 2's `OutfitBootPhase` pattern):** OutfitBootPhase runs pre-loop on towns; BridgePhase runs mid-session on overworld where random encounters are guaranteed. Reusing the main loop's encounter machinery (PostBattle dismiss, UnknownMapTrap detection, warp memory, fog) is mandatory — duplicating it inside a self-contained orchestrator would re-introduce known-fixed bugs.

**Key invariants:**
- `bridgePhaseActive == true` ⟹ `grindModeActive == false` (mutual exclusion enforced at flip site).
- BridgePhase is **skipped silently** if its dependencies are unavailable (no Gemini key, no viewport source). LLM executor takes over (current behavior).
- BridgePhase **never wedges the session**. On retry exhaustion it bails to the LLM executor; sticky bail prevents thrash if `BRIDGE` re-fires.
- All persistence is via existing `landmarkMemory` (filesystem-persistent, no savestate hash).

**Skip semantics on session resume:**
- No state file means: on resume, the agent re-runs strategic-tick → `BRIDGE` re-fires → BridgeTick re-enters.
- If `TEMPLE_ENTRY` landmark exists → `DiscoverChaosShrine` is skipped (`tofEntranceTile` hydrated from landmark on construction).
- If party is already adjacent to the cached tile (typical post-bridge resume) → `Reached` returns on the first iteration, flag flips off, ~0 cost.
- Otherwise walk resumes from current position toward the cached tile.

**Out of scope for Spec 3a:**
- Spec 3b: enter ToF interior, coord-targeted interior nav, find Garland tile.
- Spec 3c: Garland boss fight tactics.
- Spec 3d: post-fight princess pickup, return to King Coneria, bridge cutscene.

## 3. Components

### 3.1 `DiscoverChaosShrine` skill

**Location:** `knes-agent/src/main/kotlin/knes/agent/skills/DiscoverChaosShrine.kt`

**Pre-condition:** Party is on the overworld (`phase is FfPhase.Overworld`) somewhere north of the bridge. Vision dependencies are wired in.

**Behavior:**
1. Capture viewport screenshot from injected `ViewportSource`.
2. Call `HaikuConsult.classifyOverworldLandmark(image, kind="chaos_shrine")` (new vision method).
3. On `Found(screenX, screenY)`: derive world coordinates from `(ram.worldX, ram.worldY)` plus viewport offset; persist as `TEMPLE_ENTRY` landmark via `landmarkMemory.add(...)`; return `Discovered(worldX, worldY)`.
4. On `NotFound` or vision exception: return `NotVisible` or `ClassifyFailed(reason)`.

**Outcomes:**
- `Discovered(worldX: Int, worldY: Int)` — landmark persisted.
- `NotVisible` — vision returned `not_found`.
- `ClassifyFailed(reason: String)` — Gemini error or parse failure.

**Side effects:** at most one `landmarkMemory.add(TEMPLE_ENTRY)` per call; one trace event `bridge_tick_discover`.

### 3.2 `BridgeTick`

**Location:** `knes-agent/src/main/kotlin/knes/agent/runtime/BridgeTick.kt`

**Construction:** `BridgeTick(toolset, viewportSource, landmarkMemory, fog, vision, toolCallLog)`. Hydrates `tofEntranceTile` from `landmarkMemory.findTempleEntry()` at construction; null if not yet discovered.

**State (per instance, session-scoped):**
- `tofEntranceTile: Pair<Int, Int>?` — cached after first discover.
- `discoverAttempts: Int` (cap 3).
- `walkStallCount: Int` (cap 5 consecutive `NoMove`).
- `bailed: Boolean` — sticky once true, ignored from main loop.

**Tick logic:** `run(ram, phase): TickOutcome` returning `Continue | Reached | BailToLlm`.

```kotlin
suspend fun run(ram: Map<String, Int>, phase: FfPhase): TickOutcome {
    if (bailed) return BailToLlm  // sticky
    if (tofEntranceTile == null) {
        when (val r = DiscoverChaosShrine(...).invoke(emptyMap())) {
            Discovered -> { tofEntranceTile = r.tile; return Continue }
            NotVisible, ClassifyFailed -> {
                discoverAttempts++
                if (discoverAttempts >= 3) { bailed = true; return BailToLlm }
                return Continue
            }
        }
    }
    val (tx, ty) = tofEntranceTile!!
    val (wx, wy) = ram["worldX"]!! to ram["worldY"]!!
    val manhattan = abs(wx - tx) + abs(wy - ty)
    if (manhattan <= 1) return Reached
    val r = WalkOverworldTo(...).invoke(mapOf("targetX" to tx.toString(), "targetY" to ty.toString()))
    when {
        r.message.startsWith("Aborted") -> return Continue  // encounter; not a stall
        !r.ok || r.message.startsWith("NoMove") -> {
            walkStallCount++
            if (walkStallCount >= 5) { bailed = true; return BailToLlm }
            return Continue
        }
        else -> { walkStallCount = 0; return Continue }
    }
}
```

**Adjacency:** Manhattan distance ≤ 1 between `(worldX, worldY)` and `tofEntranceTile`. Diagonal-adjacent counts.

### 3.3 `HaikuConsult.classifyOverworldLandmark`

**Location:** existing `knes-agent/src/main/kotlin/knes/agent/vision/HaikuConsult.kt` (Gemini implementation file path TBD per existing pattern).

**Signature:**
```kotlin
suspend fun classifyOverworldLandmark(image: ByteArray, kind: String): OverworldClassification
```

**`OverworldClassification`:**
```kotlin
sealed interface OverworldClassification {
    data class Found(val screenX: Int, val screenY: Int, val costUsd: Double) : OverworldClassification
    data object NotFound : OverworldClassification
}
```

**Implementation:** Reuses existing `buildBody/postOrNull/parseEnvelope` plumbing from `classifyShopMenu` (Spec 2). Prompt template asks Gemini to identify the chaos shrine (a small temple sprite) on the overworld and return its screen-relative tile coordinates as `(x, y)` in JSON or `not_found`. Anthropic stub returns `NotFound`.

### 3.4 `LandmarkKind.TEMPLE_ENTRY`

Add new enum constant in `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkKind.kt`. Persisted by existing `landmarkMemory.save()` JSON.

### 3.5 `LandmarkMemory.findTempleEntry()`

Add helper mirroring existing `findInnkeeper()`. Returns first `TEMPLE_ENTRY` landmark or null.

### 3.6 `AgentSession` integration

**New fields:**
- `private var bridgePhaseActive: Boolean = false`
- `private var bridgeTick: BridgeTick? = null` — constructed lazily once vision deps are confirmed.

**New ctor params (both nullable, mirror Spec 2's outfit-deps pattern):**
- `bridgeVision: HaikuConsult? = null`
- `bridgeViewportSource: ViewportSource? = null` (may reuse existing `viewportSource` if same instance — pass-through).

**Behavior:**
- On session construction: if `bridgeVision != null && bridgeViewportSource != null`, instantiate `bridgeTick`; else log `bridge_phase: dependencies_unavailable` once.
- In `runStrategicTick` BRIDGE branch: change from `{ grindModeActive = false; null }` to `{ grindModeActive = false; if (bridgeTick != null) bridgePhaseActive = true; null }`.
- After existing strategic-tick block in main loop, add:
  ```kotlin
  if (bridgePhaseActive && phase is FfPhase.Overworld && strategicPlan == null) {
      when (val r = bridgeTick!!.run(ram, phase)) {
          Reached -> {
              bridgePhaseActive = false
              trace.record("bridge_phase_summary: reached at (${ram["worldX"]},${ram["worldY"]})")
              continue
          }
          BailToLlm -> {
              bridgePhaseActive = false
              trace.record("bridge_phase_summary: bailed_to_llm")
              // fall through to LLM executor
          }
          Continue -> continue
      }
  }
  ```

## 4. Data flow per BridgeTick iteration

```
                 ┌─────────────────────┐
                 │ enter BridgeTick    │
                 └──────────┬──────────┘
                            │
              ┌─────────────▼─────────────┐
              │ tofEntranceTile == null?  │
              └────┬─────────────────┬────┘
                   │ yes             │ no
                   ▼                 │
        ┌────────────────────┐       │
        │ DiscoverChaosShrine│       │
        └────────┬───────────┘       │
            ┌────┴────┐               │
        Found     NotVisible/Failed   │
            │         │               │
            ▼         ▼               │
   persist        discoverAttempts++  │
   landmark       if≥3 → BailToLlm    │
            │         │               │
            └─────────┘               │
                   │                  │
                   ▼                  ▼
              ┌─────────────────────────┐
              │ adjacent ≤ 1 ?          │
              └────┬─────────────┬──────┘
                   │ yes         │ no
                   ▼             ▼
              return Reached   WalkOverworldTo
                                    │
                                    ▼
                            ┌────────────────┐
                            │ moved? Aborted?│
                            └─┬──────────┬───┘
                              │          │
                              ▼          ▼
                    moved: stall=0    Aborted (encounter):
                    return Continue   return Continue (no stall++)
                              │
                              ▼
                    NoMove: stall++
                    if≥5 → BailToLlm
                    return Continue
```

**Per-iteration RAM reads:** `worldX`, `worldY` (already in `ram` snapshot — no extra observer call).

**Vision call cadence:** at most once per BridgeTick iteration, only when `tofEntranceTile` is unknown. After it's set, no further vision spend in this phase. Worst case: 3 calls before bail.

**Persistence write cadence:** at most once per session — on first `Discovered`, via `landmarkMemory.add(TEMPLE_ENTRY)` + `save()`.

**Trace events emitted:**
- `bridge_tick_discover` (per discover attempt: outcome, costUsd if any).
- `bridge_tick_walk` (per walk call: ok, message).
- `bridge_phase_summary` (terminal: `reached at (x,y)` | `bailed_to_llm`).

## 5. Error handling & edge cases

| Condition | Detection | Response |
|---|---|---|
| Vision deps unavailable (no Gemini key, no viewport source) | `bridgeTick == null` at session start | Log `bridge_phase: dependencies_unavailable` once. Never flip `bridgePhaseActive` on BRIDGE. LLM executor takes over (current behavior). |
| `Discover` returns `NotVisible` 3× | `discoverAttempts >= 3` | `bailed = true`, return `BailToLlm`. Likely cause: party not yet far enough north / shrine off-screen. |
| `Discover` returns `ClassifyFailed` (Gemini error) | non-200 / unparseable | Counts as discover attempt. Same 3× cap. |
| `WalkOverworldTo` returns `NoMove` 5× consecutive | `walkStallCount >= 5` | `bailed = true`, return `BailToLlm`. Likely cause: forced-encounter trap, blocked path. Existing fog/warp memory absorbs single failures; 5× indicates real obstacle. |
| `WalkOverworldTo` returns `Aborted (encounter)` | message starts with `Aborted` | NOT counted as stall. Return `Continue`. Main loop's PostBattle handler resolves the fight; next Overworld tick re-enters BridgeTick. |
| Adjacent check passes mid-walk | adjacency confirmed on next tick | `Reached` fires on the tick following the walk; no special handling. |
| `BRIDGE` decision re-fires after a previous bail in the same session | `bridgeTick.bailed == true` | Tick returns `BailToLlm` immediately (sticky). Single-shot bail per session prevents thrash. |
| Adjacent tile reached but RAM `currentMapId` already changed (party stepped onto entrance) | `currentMapId != 0` on adjacency check | Still counts as `Reached`. Persist landmark with the world coord captured pre-step. Spec 3b handles being already inside. |
| Wall-clock or skill-cap budget exhausted mid-phase | existing top-of-loop check | Returns `Outcome.OutOfBudget` from main loop. `TEMPLE_ENTRY` landmark may or may not be persisted depending on timing. Acceptable — next session retries from current party position. |

**Invariants:**
- `bridgePhaseActive == true` ⟹ `grindModeActive == false`.
- `bridgeTick.bailed == true` is sticky for the session lifetime.
- `discoverAttempts` and `walkStallCount` reset only on `Reached` (i.e., never reset within a single BridgePhase span — bail is one-shot).

## 6. Testing

### 6.1 Unit tests (~15, Kotest FunSpec, all green offline)

**`BridgeTickTest`** (~8 cases, fake `Skill` + fake vision):
- `tofEntranceTile == null` → calls `DiscoverChaosShrine`.
- `Discovered(x,y)` → persists `TEMPLE_ENTRY` landmark, sets state, returns `Continue`.
- `NotVisible` 3× → returns `BailToLlm`, `bailed = true`.
- `ClassifyFailed` 2× then `NotVisible` → returns `BailToLlm` (combined cap).
- Adjacent (Manhattan ≤ 1) → returns `Reached`.
- Walk `Aborted (encounter)` → does NOT increment stall, returns `Continue`.
- Walk `NoMove` 5× → returns `BailToLlm`.
- Sticky bail: bailed=true → re-entry returns `BailToLlm` immediately.

**`HaikuConsultClassifyOverworldLandmarkTest`** (~3 cases):
- Gemini happy path returns `Found(screenX, screenY)`.
- Gemini parse failure returns `NotFound` (no exception leaks).
- Anthropic stub returns `NotFound`.

**`LandmarkMemoryFindTempleEntryTest`** (~2 cases):
- No `TEMPLE_ENTRY` → returns null.
- One `TEMPLE_ENTRY` present → returns it; subsequent saves preserve.

**`AgentSessionBridgePhaseTest`** (~3 cases):
- BRIDGE decision + bridge deps available → `bridgePhaseActive = true`.
- BRIDGE decision + missing deps → `bridgePhaseActive` stays false; logs `dependencies_unavailable`.
- `BridgeTick.Reached` outcome → `bridgePhaseActive = false`, `bridge_phase_summary: reached` traced.

### 6.2 No e2e test in Spec 3a

Without savestate gating (per project policy), an e2e test would have to play through Outfit boot + full grind to `min_level=3` before BridgePhase kicks in (~5 min wall-clock + API spend per CI run). Not justified for Spec 3a alone.

Rely on:
- Unit tests for component correctness.
- Manual validation runs (3× sessions from genuine session start, user-driven).

### 6.3 Regression coverage

All 35 Spec 1 + (post-cleanup) ~20 Spec 2 unit tests must remain green. Existing 3 baseline failures (Coneria8VisualDiffTest, ConeriaTownEmpiricalDiscoveryTest, ExploreOverworldFrontierTest) — unchanged status.

### 6.4 Manual validation (user-driven, post-merge)

Run command:
```bash
rm -f ~/.knes/ff1-landmarks.json
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes --wall-clock-cap-seconds=600 --cost-cap-usd=2.5 --max-skill-invocations=120"
```

3× sessions. Capture from each trace:
- `bridge_phase_summary` outcome (`reached` / `bailed_to_llm`).
- Discovered `tofEntranceTile` coords (compare across runs — should be identical if vision is consistent).
- Encounter count during transit (informational).

**Pass criterion:** ≥ 2/3 sessions reach `Reached` within wall-clock budget.

## 7. Open questions / known concerns

1. **ToF entrance coord is unknown a priori.** Vision is the only mechanism in Spec 3a; if Gemini consistently misses the chaos shrine sprite across the visible viewport, BridgePhase always bails. Mitigations:
   - Spec 3a starts party near bridge after BRIDGE decision; BFS path through bridge typically lands the party 5-10 tiles N where the shrine should be visible in the 16×16 viewport.
   - If validation shows zero hits across 3 runs, follow-up: add a hardcoded-coord fallback derived from FF1 ROM disasm.

2. **`WalkOverworldTo` BFS over bridge.** The bridge tile is walkable per the FF1 overworld decoder; BFS should route through it without special handling. **First validation run will confirm.**

3. **`bridgeViewportSource` may collapse with existing `viewportSource`.** If they're always the same instance, drop the second param in implementation. Final shape decided during `writing-plans`.
