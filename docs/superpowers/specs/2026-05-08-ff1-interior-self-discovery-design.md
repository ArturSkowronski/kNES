# FF1 Interior Self-Discovery Landmark Scan — Design (Spec 4 / Spec 3a unblocker)

**Date:** 2026-05-08 **Branch target:** TBD (cut from `ff1-bridge-and-walk-to-tof` HEAD `ed9a6e9` after Spec 2 lands) **Parent context:** Spec 3a (`2026-05-07-ff1-bridge-and-walk-to-tof-design.md`) is functionally implemented + 5 empirical-run fixes landed locally, but its end-to-end validation is **upstream-blocked** by cold-start shop discovery in Coneria. Three autonomous runs hit `boot_shop_not_found` because `discoverWeaponShop`'s `maxAttempts = count(NPC_SHOPKEEPER).coerceAtLeast(1).coerceAtMost(4)` collapses to 1 attempt with no seed, and the underlying `WalkInteriorVision` is goal-blind (asks Sonnet only for "next direction", not "is a shopkeeper visible"). This spec replaces the brittle probe with a goal-aware self-discovery explorer that scans interior frames for any visible landmark (NPCs, stairs, chests, signs, dialogue triggers) using two-pass vision and persists confirmed landmarks across runs.

## 1. Goal & success criteria

**Goal:** Cold-start `OutfitBootPhase` in Coneria reliably finds the weapon shopkeeper without per-town hardcoded seeds, by composing the existing `WalkInteriorVision` (next-step direction, unchanged) with a new frame-triggered two-pass vision scanner that auto-persists confirmed landmarks. The mechanism is generic — same code reusable for Pravoka, Elfheim, etc., and for non-shop landmarks (kings, stairs, chests, signs, dialogue triggers).

**Success criteria (measurable, per session):**

1. **Functional (MVP):** After `OutfitBootPhase` enters Coneria interior, in ≥ 2/3 cold-start manual validation runs the explorer returns `Found(NPC_SHOPKEEPER, kind=weapon)` within ≤ 50 walk steps and ≤ $0.50 incremental vision cost. `BuyAtShop` then succeeds for at least 1 character.
2. **Persistence:** confirmed landmarks (kind, mapId, localX/Y, refined note) persist to `~/.knes/ff1-landmarks.json` via `LandmarkMemory.recordIfNew()`. Subsequent runs skip the explorer for cached landmarks (existing `cachedShop` path in `OutfitBootPhase`).
3. **Stability:** zero regressions in 302 baseline tests. `WalkInteriorVision` unchanged. 3 pre-existing baseline failures unchanged.
4. **Resilience:** any single iter failure (vision API error, malformed JSON, OAM unavailable) does not bail the explorer; only sticky degradation (3× consecutive malformed Pass 1) or step-cap exhaustion returns a non-`Found` outcome.
5. **Genericity (verified by code review + unit tests, not full e2e):** explorer is callable for any `LandmarkKind` + predicate, not weapon-shop-specific. Same call shape works for `NPC_KING` in Coneria castle, validated by unit tests; multi-town e2e deferred.

**Non-goals:**

- Multi-town empirical validation (Pravoka, Elfheim, etc.). Generic by construction; validated by Coneria-only in MVP.
- Castle/dungeon exploration end-to-end. Same explorer applies, but no e2e runs.
- Pre-seeding landmarks from FF1 disasm ROM tables (option B from handoff). Orthogonal effort; not needed if self-discovery works.
- LLM-driven goal selection. The executor LLM does NOT choose when to call this; deterministic phases (`OutfitBootPhase`, future `BridgeTick` interior probes) call it as a Kotlin API. Per `autonomy_principle.md`, explorer is infrastructure, not a skill exposed to the agent.
- OAM cross-reference, three-tier confidence ladder, goal hierarchy (primary/secondary/tertiary), sprite-dict from CHR-ROM, critique instance — see §9 Future work / Escalation.

## 2. Architecture

A new compositional class `InteriorExplorer` sits between `OutfitBootPhase` and the existing `WalkInteriorVision` skill. It owns the explore-loop logic and composes three internal components: the existing `WalkInteriorVision` (untouched), a new `InteriorScanner` (two-pass vision: candidate + verify), and a new `FrameChangeDetector` (heuristic trigger so we don't scan on every step).

```
┌──────────────────────────────────────────────────────────────────────┐
│ OutfitBootPhase  (existing, AgentSession.kt:615-748)                 │
│   ...                                                                │
│   activeShop = cachedShop ?: explorer.exploreUntilFound(             │
│       goal     = LandmarkKind.NPC_SHOPKEEPER,                        │
│       predicate= { it.note.contains("kind=weapon") },                │
│       capSteps = 50)                                                 │
│   ...                                                                │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│ InteriorExplorer                                  (NEW, ~150 LoC)    │
│                                                                      │
│   exploreUntilFound(goal, predicate, capSteps): ExploreOutcome       │
│   exploreFully(capSteps): ExploreOutcome                             │
│                                                                      │
│   per iter:                                                          │
│   ├─ capture screenshot + emulator state (OAM, RAM coords)           │
│   ├─ if FrameChangeDetector.shouldScan(curr, prev) → scan            │
│   │     ├─ Scanner.scanCandidates(screenshot)        [Pass 1, Haiku] │
│   │     └─ for each candidate w/ confidence ≥ 0.5:                   │
│   │           Scanner.verifyCandidate(focused, c)    [Pass 2, Pro]   │
│   │           if Confirmed → LandmarkMemory.recordIfNew + save       │
│   ├─ goal check on LandmarkMemory.findByKind(goal).filter(predicate) │
│   │     if non-empty → return Found(landmark)                        │
│   ├─ WalkInteriorVision.step()             ← existing, UNCHANGED     │
│   └─ check caps: capSteps reached → NotFoundCapReached               │
│                  3× sticky stuck     → StuckBailout                  │
└──────────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌─────────────────┐  ┌──────────────────┐  ┌────────────────────────┐
│ WalkInterior    │  │ InteriorScanner  │  │ FrameChangeDetector    │
│ Vision          │  │  (NEW, ~120 LoC) │  │  (NEW, ~40 LoC)        │
│  (UNCHANGED)    │  │                  │  │                        │
│ Sonnet 4.6      │  │ Pass 1 (Haiku):  │  │ Primary: PPU OAM delta │
│ next-step       │  │   scanCandidates │  │   newSpriteSlot count  │
│ direction       │  │ Pass 2 (Pro):    │  │   ≥ 1 → trigger        │
│                 │  │   verifyCand.    │  │ Fallback: 16×15 pixel  │
│                 │  │ Persist confirmed│  │   grid hash diff       │
└─────────────────┘  └──────────────────┘  └────────────────────────┘
```

**Why composition (option C), not inline (A) or peer-skill (B):**

- (A) Inline (drugi vision call wewnątrz `WalkInteriorVision`): puchnie `WalkInteriorVision` do walk + scan + verify + persist + goal-check w jednej klasie. Trzy concerns. Test-unfriendly.
- (B) Peer skill obok walk, sterowane przez `OutfitBootPhase`: state frame-diff musi być współdzielony, orkiestracja przecieka do `OutfitBootPhase` (już 130+ LoC).
- (C) Composition: każdy komponent ma jeden cel, testowalny izolowanie, `WalkInteriorVision` zostaje bez zmian (per `autonomy_principle.md`), `InteriorScanner` jako pure function (screenshot → candidates → confirmed) bez zależności od emulatora.

**Key invariants:**

- `WalkInteriorVision` unchanged — its scope is "next step direction", end of story. Goal-awareness lives in `InteriorExplorer`, never leaks into the navigator.
- `InteriorScanner` is **stateless** — `scanCandidates` and `verifyCandidate` are pure functions of (screenshot, candidate). State (OAM history, scan counters) lives in `InteriorExplorer` or `FrameChangeDetector`.
- `LandmarkMemory.recordIfNew` is the single persistence path — explorer never writes JSON directly.
- Single-iter failures never bail the explorer. Only sticky degradation (3× malformed Pass 1) or cap exhaustion returns non-`Found`.
- Persistence is **best-effort** for the current call — in-memory state is sufficient for `Found` to fire even if disk write fails.

## 3. Vision prompts

### 3.1 Pass 1 (candidate scan, Anthropic Haiku 4.5)

System prompt (`SYSTEM_INTERIOR_SCAN`):

```
You are reading a Final Fantasy 1 (NES) interior screenshot. The image is
256x240 px, a 16-tile-wide x 15-tile-tall viewport (each tile 16x16 px).
The party (4 sprites overlapping into one figure) renders at tile (8, 7).

Identify ALL visible non-party landmarks. Possible kinds:
- "shopkeeper"        — NPC behind a counter, often dressed distinctively
- "king"              — NPC on a throne / royal sprite
- "innkeeper"         — NPC near a bed/inn counter
- "generic_npc"       — generic townsperson/villager (dialogue trigger)
- "stairs_up"         — stair sprite leading up
- "stairs_down"       — stair sprite leading down
- "chest"             — treasure chest (open or closed)
- "sign"              — sign or tablet
- "exit_tile"         — door/staircase clearly leading outside

Output JSON only. Schema:
{"candidates":[{"kind":"<kind>","screenX":<int 0..15>,
                "screenY":<int 0..14>,"confidence":<float 0..1>}]}

If no landmarks visible: {"candidates":[]}.

Do NOT guess. confidence ≥ 0.7 only when you can see the sprite clearly.
Return ONLY JSON.
```

User text: `"Identify visible landmarks."`

### 3.2 Pass 2 (verify, Gemini 2.5 Pro)

System prompt (`SYSTEM_VERIFY_LANDMARK`):

```
You are verifying a Final Fantasy 1 (NES) interior landmark candidate.
The image is a focused 32x32 pixel crop centered on tile coordinates the
candidate scanner reported. The candidate's claimed kind is provided in
the user message.

Two-step task:
(1) Confirm or reject that the candidate kind matches what you see.
(2) If confirmed AND the candidate kind is "shopkeeper", additionally
    classify the shop type from visual context (counter contents, NPC
    sprite palette): weapon|armor|whiteMagic|blackMagic|item|unknown.
    For non-shopkeeper kinds, refinedShopKind = null.

Output JSON only. Schema:
{"confirmed": true,
 "refinedKind":"<same as candidate kind>",
 "refinedShopKind":"<weapon|armor|whiteMagic|blackMagic|item|unknown>"
                    OR null for non-shopkeeper,
 "reason":"<short>"}
or
{"confirmed": false, "reason":"<short>"}

Confirmed examples by kind:
- shopkeeper: NPC behind a counter; refinedShopKind required.
  - weapon shop:  counter shows weapons (sword/axe/dagger/hammer/staff).
  - armor shop:   counter shows shields/helms/body armor.
  - whiteMagic:   CURE/HARM/FOG/RUSE scroll sprites.
  - blackMagic:   FIRE/LIT/SLEP/LOCK scroll sprites.
  - item shop:    potion/tent/cabin sprites.
  - unknown:      shopkeeper sprite clear but counter unclear.
- innkeeper: NPC near a bed/inn counter; refinedShopKind = null.
- king: NPC on throne; refinedShopKind = null.
- generic_npc / chest / sign / stairs_up / stairs_down / exit_tile:
  refinedShopKind = null.

Note: "innkeeper" is its own kind. Do NOT classify a shopkeeper as
"inn" — if you see a bed/inn context, the kind is "innkeeper", not
"shopkeeper" with shop type "inn".

Return ONLY JSON.
```

User text: `"Verify candidate kind=<kind> at tile (<x>, <y>)."`

The persisted `Landmark.note` is then formatted as:

- For confirmed shopkeeper: `"kind=<refinedShopKind>; verified=pass2; reason=<...>"` (matches existing `cachedShop` lookup `note.contains("kind=weapon")`).
- For other confirmed kinds: `"verified=pass2; reason=<...>"` — `kind` field of `Landmark` already carries the discriminator.

### 3.3 Existing `SYSTEM_CLASSIFY` (interior NPC classify) — comparison

The existing `SYSTEM_CLASSIFY` prompt (in `GeminiVisionConsult.kt`) classifies a *single*, already-targeted NPC after the agent has approached it. It is single-purpose and does not enumerate the screen. We keep it for `DiscoverShop`'s post-walk shopkeeper interaction (4×A → BUY menu); it is not replaced by Pass 1/Pass 2.

## 4. Data flow

One `exploreUntilFound(NPC_SHOPKEEPER, predicate=kind=weapon, capSteps=50)` call:

```
[entry] OutfitBootPhase has entered mapId=8 (post-warp settle)
   │
   ▼
InteriorExplorer.exploreUntilFound(...)
   │
   ├─ step loop iter N (N: 1..capSteps):
   │     │
   │     ├─ capture screenshot (256×240) + emulator state (OAM snapshot, RAM coords)
   │     │
   │     ├─ FrameChangeDetector.shouldScan(currOAM, prevOAM)?
   │     │     ├── NO  → goto walk step
   │     │     └── YES (≥1 new sprite slot OR first iter)
   │     │
   │     ├─ [trigger]  InteriorScanner.scanCandidates(screenshot)
   │     │     ├── HaikuConsult.scanInteriorCandidates(image)
   │     │     │     POST → Anthropic Haiku 4.5
   │     │     └── filter confidence ≥ 0.5 → List<CandidateLandmark>
   │     │
   │     ├─ [verify each candidate]
   │     │     ├── crop screenshot 32×32 px around candidate tile
   │     │     ├── HaikuConsult.verifyLandmark(focused, candidate)
   │     │     │     POST → Gemini 2.5 Pro
   │     │     └── if Confirmed:
   │     │           localXY = ViewportMap.localToWorld(screenX, screenY,
   │     │                                              partyRamCoords)
   │     │           Landmark(refinedKind, mapId, localXY,
   │     │                    note=refinedNote, visited=false,
   │     │                    discoveredRunId=current)
   │     │           LandmarkMemory.recordIfNew + save
   │     │           emit trace: interior_scan_confirmed
   │     │
   │     ├─ [goal check]
   │     │     foundLandmark = LandmarkMemory.findByKind(goal)
   │     │                       .firstOrNull(predicate)
   │     │     if found → return Found(foundLandmark)
   │     │
   │     ├─ [walk step]  WalkInteriorVision.step()      [unchanged]
   │     │     Sonnet 4.6 returns NORTH/SOUTH/EAST/WEST/EXIT/STUCK/UNCLEAR
   │     │     emulator advances frames; party RAM coords update
   │     │
   │     └─ caps:
   │           capStepReached → NotFoundCapReached(stats)
   │           3× walk STUCK  → StuckBailout("walk-stuck-after-N")
   │           3× malformed P1 → StuckBailout("pass1-degraded")
   │
   ▼
[outcome] OutfitBootPhase: Found → cachedShop=landmark, BuyAtShop
                           NotFoundCapReached → trace + fall-through
                           StuckBailout       → trace + fall-through
                           EncounterTriggered → trace + return (anomaly)
```

**Per-call state:**

- `prevOAMSnapshot: Set<SpriteSlot>` — for FrameChangeDetector.
- `scanCount: Int`, `confirmedThisRun: Set<Landmark>`, `consecutiveMalformed: Int`.

**Cross-run state:** unchanged — `LandmarkMemory` JSON.

**Cost projection per Coneria cold-start run:**

- Walk: 30-50 Sonnet 4.6 calls = \~$0.30-0.50 (existing baseline, unchanged).
- FrameChangeDetector trigger rate: \~30-40% of steps → \~12-20 scans.
- Pass 1 (Haiku): 12-20 × \~$0.002 = $0.02-0.04.
- Pass 2 (Gemini Pro): \~10-30 verifies × \~$0.005 = $0.05-0.15.
- **Total incremental:** \~$0.07-0.19 per run.

## 5. Error handling matrix

| Failure | Detection | Reaction |
| --- | --- | --- |
| Vision API timeout / 5xx / rate-limit (Pass 1 or Pass 2) | Exception from `HaikuConsult` | 1× retry with jitter (200ms + rand). Further fail → treat iter as `Skipped(reason="vision-error")`, continue walk. Trace: `interior_scan_error`. |
| Pass 1 malformed JSON | `JSON_OBJECT` regex miss / parse exception | Log, `Skipped(reason="malformed-pass1")`. After 3 consecutive in same call → `StuckBailout("pass1-degraded")`. |
| Pass 2 malformed JSON | jw | Treat candidate as `Rejected(reason="malformed-pass2")`. Other candidates continue. |
| PPU OAM API unavailable | `mcp__knes__get_state` lacks OAM field / throws | `FrameChangeDetector` switches to fallback: 16×15 grid pixel hash, threshold ≥ 3 changed tiles. Sticky for the call. Trace: `frame_detector: fallback=pixel-hash`. |
| Pass 1 false-positive epidemic (Pass 2 rejects &gt; 80% of candidates after N ≥ 5 scans) | Counter | Log warning, do NOT bail — castle/dungeon legitimately has many "candidate-but-not" sprites. Telemetry only. |
| Walk STUCK signal from `WalkInteriorVision` (3× consecutive STUCK) | Existing sticky stuck in `WalkInteriorVision` | Return `StuckBailout(reason="walk-stuck-after-N", confirmedSoFar=K)`. |
| Encounter triggered in interior (defensive — should not happen) | RAM phase change → `Battle` | Return `EncounterTriggered`. |
| `screenTile → localXY` mapping fail | `ViewportMap.localToWorld` returns null / throws | Treat candidate as `Rejected(reason="invalid-coords")`. Trace anomaly. |
| Persistence write fail (`AtomicJsonWriter` exception) | Exception from `LandmarkMemory.save()` | Log, keep landmark in-memory for this call. Retry save 1×. Further fail → `confirmedThisRun` does not persist; current call still returns `Found` if goal hit. Graceful degradation. |
| Cap reached mid-verify | Counter check before each iter | Finish current scan's pending Pass 2 verifications, then `NotFoundCapReached`. Cap blocks new walk steps, not in-flight verification. |
| Duplicate candidate across consecutive frames (same NPC visible 3 frames) | `LandmarkMemory.recordIfNew` dedup on `(kind, mapId, localX, localY)` | No-op. Telemetry shows "confirmed-but-already-known". Pass 2 still runs (stateless), giving extra signal. |

**Invariants:**

- Single-iter failure never aborts the explorer. Only sticky degradation or cap exhaustion does.
- Pass 1 and Pass 2 are independent. Pass 2 fail doesn't undo Pass 1; Pass 1 fail just skips the iter.
- Persistence is opt-in for current-call success. In-memory state suffices for `Found`.
- Cap blocks new work, not cleanup of in-flight work.

**Anti-patterns explicitly avoided:**

- No retry on malformed JSON (won't fix on second call with same input).
- No dedicated circuit breaker (3-consecutive sticky counters suffice).
- No fallback "Pass 1 only without Pass 2" when Gemini Pro is down — Pass 2 down → no candidates confirmed for this call; next call retries.
- No deduplication of candidates within a single scan (Pass 1 rarely duplicates).

## 6. Testing strategy

### 6.1 Unit tests (\~25-30 new, all deterministic)

| Component | Tests | Mocks |
| --- | --- | --- |
| `FrameChangeDetector` | (1) first frame always triggers; (2) party-only motion (slots 0-3) doesn't trigger; (3) new OAM slot triggers; (4) fallback to pixel-hash when OAM null; (5) sticky fallback per call. | `OamSnapshot` test fixtures + `FakeEmulatorState`. |
| `InteriorScanner` Pass 1 | (1) parse valid JSON list; (2) malformed JSON throws; (3) confidence filter ≥ 0.5; (4) empty candidates list; (5) prompt shape (snapshot test). | `FakeHaikuConsult` with scriptable responses. |
| `InteriorScanner` Pass 2 | (1) confirmed → Landmark with refined note; (2) rejected → reason; (3) malformed → `Rejected("malformed")`; (4) crop logic 32×32 around candidate tile. | `FakeGeminiVisionConsult` + screenshot fixtures. |
| `screenTile → localXY` mapping | (1) party at center, NPC at (5,3) → localXY relative; (2) NPC outside viewport → null; (3) ViewportMap throws → null. | Real `ViewportMap` + RAM coord fixtures. |
| `InteriorExplorer.exploreUntilFound` | (1) Found in iter 1; (2) Found in iter 5 after 3 rejected; (3) NotFoundCapReached after cap with 0 confirmed; (4) StuckBailout after 3× WalkInteriorVision STUCK; (5) Vision API error in iter 2 → continues, fail in iter 5 → continues; (6) 3× malformed Pass 1 in row → StuckBailout; (7) goal predicate filters: kind=weapon found over kind=armor; (8) Encounter mid-explore → EncounterTriggered. | Stubbed `WalkInteriorVision`, `InteriorScanner`, `LandmarkMemory` (in-memory). |
| `LandmarkKind` enum extension | Compile-time + JSON round-trip for `CHEST`, `SIGN`, `DIALOGUE_TRIGGER`. | None. |

### 6.2 Integration tests (gated `KNES_LIVE_VISION=true`, skipped in CI)

| Test | Scope | Cost |
| --- | --- | --- |
| `InteriorExplorerLiveTest` | Real ROM → mapId=8 entry → `exploreUntilFound(NPC_SHOPKEEPER, kind=weapon)` → expect `Found` in &lt; 50 steps. | \~$0.20 + 2-3 min wall clock. |
| `InteriorScannerSnapshotTest` | Real Gemini Pro + 5 fixed Coneria interior screenshot fixtures → expected candidates. Updated when prompt changes. | \~$0.05. |

### 6.3 Empirical validation runs (manual, on-demand only — NOT in CI)

- 1× cold-start full agent run, empty `~/.knes/`, expected `boot_outfit_summary: weaponsBought ≥ 1`. Sukces = MVP ready.
- 1× warm-start follow-up run (with persistent landmarks from previous run), expected `cachedShop` hit, no new Pass 1/Pass 2 calls. Validates persistence.

### 6.4 Out of scope for testing

- Pass 2 verify accuracy across full candidate-type matrix — no ground truth dataset.
- `WalkInteriorVision` — already covered, unchanged.
- Cross-town generalization — MVP is Coneria-only.
- Cost regression tests — telemetry trace suffices.

### 6.5 Test fixtures to add

- `knes-agent/src/test/resources/interior-screenshots/`: `coneria_entry.png`, `coneria_shop_visible.png`, `coneria_corridor.png`, `coneria_king_room.png`, `coneria_stairs.png`. Real screenshots from runs.
- `knes-agent/src/test/resources/oam/`: corresponding `coneria_*` JSON fixtures.
- `FakeHaikuConsult` extension methods: `scriptScanCandidates(List<Response>)`, `scriptVerifyLandmark(Map<Candidate, Result>)`.

## 7. Telemetry / observability

New trace events in `~/.knes/runs/<id>/trace.jsonl`:

- `interior_scan_triggered: oamDelta=N, totalScansThisRun=K, fallback=<pixel|oam>`
- `interior_scan_candidates: count=N, kinds=[shopkeeper, generic_npc, stairs_down]`
- `interior_scan_confirmed: kind=<X>, mapId=<M>, localXY=(lx,ly), note=<...>, runId=<R>`
- `interior_scan_rejected: candidateKind=<X>, reason=<malformed|invalid-coords|pass2-rejected>, pass2Reason=<...>`
- `interior_scan_error: pass=<1|2>, code=<X>, retried=<Y>, finalSkipped=<bool>`
- `interior_explore_outcome: <Found|NotFoundCapReached|StuckBailout|EncounterTriggered>, scans=N, confirmed=M, candidates=K, walkSteps=W, costUsd=C`
- `frame_detector: fallback=pixel-hash, reason=<oam-unavailable|...>`

Existing trace events (`boot_outfit_summary`, `boot_shop_probe`, etc.) unchanged.

## 8. Persistence model changes

### 8.1 `LandmarkKind` enum extension

Add three new values to `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt`:

```kotlin
enum class LandmarkKind {
    TOWN_ENTRY, CASTLE_ENTRY,
    NPC_KING, NPC_SHOPKEEPER, NPC_INNKEEPER,
    STAIRS_UP, STAIRS_DOWN,
    EXIT_TILE,
    CHEST,                      // NEW
    SIGN,                       // NEW
    DIALOGUE_TRIGGER,           // NEW (generic NPCs)
    UNKNOWN
}
```

### 8.2 `Landmark` data class — no change

Existing fields (`id`, `kind`, `worldX/Y`, `mapId`, `localX/Y`, `mapIdInterior`, `visited`, `note`, `discoveredRunId`) cover all needs. `note` is used for refined classification (e.g., `"kind=weapon; items=...; pass1Conf=0.8; pass2Verified=true"`).

### 8.3 JSON migration

Existing `~/.knes/ff1-landmarks.json` files written before this spec parse fine — new `kind` values only appear in landmarks written by this spec. Old serializers must accept new kinds without throwing (unknown enum value falls back to `LandmarkKind.UNKNOWN` or skips silently — to be confirmed in implementation).

## 9. Future work / Escalation if MVP doesn't deliver

If the cold-start Coneria validation run hits `NotFoundCapReached` or `StuckBailout`, escalate in this order:

1. **R0: Use Gemini Pro for just everything. Not worry about a cost, just switch model and check results**

2. **R1: OAM cross-reference as Pass 1.5.** Insert deterministic OAM check between Pass 1 and Pass 2 — if no sprite slot exists at the candidate's pixel position, skip Pass 2. Eliminates 30-50% of Gemini Pro calls and bad landmarks at $0. \~50 LoC.

3. **R2: Three-tier confidence ladder** (`candidate | confirmed | verified-on-use`). Persist Pass 1 candidates with `candidate` flag; bump to `confirmed` after Pass 2; bump to `verified-on-use` after `BuyAtShop`/equivalent succeeds. Allows callers to filter by confidence level.

4. **R3: Goal hierarchy** (`primary | secondary | tertiary`) in `exploreUntilGoals(...)` API. `primary` is hard target; `secondary`/`tertiary` are persisted-if-seen but don't block return. Amortizes one explore call across multiple downstream specs.

5. **R4: Token-per-call telemetry + 100k context warning.** Add `tokensIn`/`tokensOut` per trace event. Warn if cumulative &gt; 80k tokens (GPP-validated context-degradation threshold).

6. **R5: Sprite-dict from CHR-ROM.** Build a deterministic mapping (sprite tile ID → kind) by extracting CHR-ROM patterns. Replaces Pass 2 entirely for sprite-based discrimination. Requires new CHR extraction tooling. Spec 5 candidate.

7. **R6: Critique instance.** Temporary Gemini Pro reviewing the current run's trace for stuck-loops and goal contamination (GPP's `criticizeAgent`). Useful once we have &gt; 1 town to explore.

8. **R7: Pre-seed landmarks from FF1 disasm** (handoff option B). Orthogonal — could run alongside self-discovery to give explorer a head start. Not needed if self-discovery alone meets the success criteria.

**Design principle (not escalation, but worth restating):** the `capSteps` parameter is a HARD stop. No implicit fallback "search a bit more" is allowed. Anti-pattern: GPP's "obsessive tile coverage" loops drained budget without progress. If `capSteps` reached, return `NotFoundCapReached` and let the caller decide whether to re-call.

## 10. Known concerns / open questions

1. **mapId=8 town vs castle confusion.** `Coneria8VisualDiffTest` and `ConeriaTownFixtureBuilderTest` both reference mapId=8 but appear to disagree on whether it is the town or the castle. Resolve before running validation: one targeted run logging `currentMapId` immediately after warp settle. Implementation must not assume; reads `mapId` from RAM.

2. **PPU OAM API availability.** Spec assumes `mcp__knes__get_state` (or emulator core) exposes OAM (Object Attribute Memory — 256 bytes, 64 4-byte sprite slots). If not, `FrameChangeDetector` falls back to pixel-grid hash. Verification step in plan phase.

3. **Is** `DiscoverShop`**'s 4×A still correct after** `WalkInteriorTo(seed)`**?** When the explorer points at a confirmed shopkeeper landmark at `(localX, localY)`, `BuyAtShop` is then called by `OutfitBootPhase` and presumably walks-to-and-talks-to. The 4×A pattern (approach + dialog → BUY/Sell → BUY) may need adjustment if the party arrives at a different facing direction. To verify in implementation by reading `BuyAtShop` source.

4. **Pass 1 prompt rates** `confidence` **— is Haiku's confidence calibration usable?** Threshold 0.5 chosen conservatively. May need tuning after first empirical scan results. GPP's lesson: don't trust LLM confidence absolutely — use it only as a coarse filter.

5. **Pass 2** `refinedNote` **format.** Spec defines `"kind=weapon; items=..."` shape but `OutfitBootPhase`'s existing `cachedShop` lookup checks `note.contains("kind=weapon")`. Format must match exactly. Implementation must use the same string format both directions.

6. **Frame-diff trigger rate empirically.** Spec assumes 30-40% of steps trigger scan. If real rate is much higher (e.g., NES sprite flicker causes spurious OAM deltas) cost projection blows up. Telemetry will reveal post-MVP.

## 11. Acceptance criteria / Definition of Done

- [ ] `LandmarkKind` enum extended with `CHEST`, `SIGN`, `DIALOGUE_TRIGGER`. Existing landmarks JSON files load without errors.

- [ ] `HaikuConsult` interface gains `scanInteriorCandidates(image)` and `verifyLandmark(image, candidate)`. Anthropic and Gemini implementations both compile; `FakeHaikuConsult` supports scripting both.

- [ ] `InteriorScanner` class implemented with Pass 1 + Pass 2 flow. Unit tests (5 Pass 1 + 4 Pass 2) green.

- [ ] `FrameChangeDetector` class implemented with OAM primary + pixel-hash fallback. Unit tests (5) green.

- [ ] `InteriorExplorer` class implemented with `exploreUntilFound(...)` and `exploreFully(...)`. 8 unit tests green.

- [ ] `OutfitBootPhase` updated: `discoverWeaponShop` replaced (or wrapped) with `explorer.exploreUntilFound(NPC_SHOPKEEPER, predicate=kind=weapon, capSteps=50)`. `cachedShop` path unchanged.

- [ ] All 7 new trace events emitted at appropriate points; existing traces unchanged.

- [ ] 302 baseline tests still green; 3 baseline failures unchanged.

- [ ] Empirical run #1 (cold start, empty `~/.knes/`): `boot_outfit_summary: weaponsBought ≥ 1`. PASS.

- [ ] Empirical run #2 (warm start, persisted landmarks): `cachedShop` hit logged, no new Pass 1/Pass 2 calls in `interior_scan_*` traces.

- [ ] Open question §10.1 (mapId=8 town vs castle) resolved via run-time RAM log; documented in implementation notes.

- [ ] Open question §10.2 (PPU OAM availability) verified or fallback engaged; documented.

## 12. Repo paths

- Spec: `docs/superpowers/specs/2026-05-08-ff1-interior-self-discovery-design.md` (this file)
- Plan: TBD (to be written by `writing-plans` skill after this spec is approved)
- New code paths:
  - `knes-agent/src/main/kotlin/knes/agent/runtime/InteriorExplorer.kt`
  - `knes-agent/src/main/kotlin/knes/agent/skills/InteriorScanner.kt`
  - `knes-agent/src/main/kotlin/knes/agent/perception/FrameChangeDetector.kt`
  - `knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt` (interface extension)
  - `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt` (Pass 1 implementation)
  - `knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt` (Pass 2 implementation)
- Modified paths:
  - `knes-agent/src/main/kotlin/knes/agent/perception/LandmarkMemory.kt` (enum extension)
  - `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` (`runOutfitBootPhase` calls explorer)

---

**End of design.** Plan to follow via `writing-plans` skill once user approves.