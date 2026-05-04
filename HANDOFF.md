# FF1 Koog Agent — Handoff (V5.12 → next)

**Branch:** `ff1-agent-v2-4` of `/Users/askowronski/Priv/kNES-ff1-agent-v2`
**HEAD:** `3460174` — V5.12 live integration smoke test for InteriorMemory stack
**Required env:** `ANTHROPIC_API_KEY` (only for live agent runs; not for tests below)

---

## Read first (in this exact order)

1. `docs/superpowers/notes/2026-05-04-v512-memory-stack-evidence.md` — V5.12
   live evidence for the new GPP-inspired InteriorMemory stack + the
   POI_STAIRS-vs-castle-stairs gotcha that emerged from it.
2. `docs/superpowers/notes/2026-05-04-mapid-discovery/v57-mapid8-is-castle.md` —
   V5.7 evidence that mapId=8 is Coneria Castle, not Coneria Town.
3. `docs/superpowers/notes/2026-05-04-mapid-discovery/fix-b-research.md` — V5.5
   Disch FF1 disassembly cross-check; canonical addresses; 3-phase fix proposal.
4. `docs/superpowers/notes/2026-05-04-mapid-discovery/report.md` — V5.3 RAM-diff
   findings, candidate map-id bytes, all evidence from the discovery probe.
5. `knes-debug/src/main/resources/profiles/ff1.json` — V5.6 profile (mapflags,
   smPlayerX/Y, sm_scroll, curTileset, facing, vehicle).
6. `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMemory.kt` — V5.8
   persistence layer (POI / VISITED / EXIT_CONFIRMED per mapId).
7. `knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt` —
   V5.10 priority logic (memory > viewport classifier > south-edge fallback).
8. `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt` — classify()
   dispatches on mapflags; Indoors party from sm_player.
9. `knes-agent/src/test/kotlin/knes/agent/perception/V58InteriorMemoryLiveTest.kt`,
   `V56FoundationVerificationTest.kt`, `V56InteriorPathfindingTest.kt`,
   `MapIdDiscoveryTest.kt` — V5.x test suite (all live boot, no API key needed).

---

## TL;DR — where we are (2026-05-04 evening, post-V5.12)

V5.6 fixed the foundation (sm_player_x/y vs scroll, mapflags vs locType).
V5.7 added tap-precision movement and proved mapId=8 is Coneria CASTLE.
**This evening (V5.8→V5.12) added the GPP-inspired InteriorMemory stack**
— a persistent POI/visited layer that lets the pathfinder learn from past
runs and lets the vision navigator know "explore unvisited area first".
Empirically verified on a live ROM: agent autonomously discovered STAIRS
at (12,18) in Coneria castle and persisted it across sessions, and a
pre-populated POI overrode the south-edge fallback exactly as designed.

A fresh blocker surfaced from the V5.12 evidence run: **POI_STAIRS in
castles is sub-map navigation, not exit** — the agent reaches STAIRS,
A-taps, transitions to a different sub-map, never to overworld. See
`2026-05-04-v512-memory-stack-evidence.md` for three options to fix.

### V5.6 (still standing) what was fixed

- `localX/localY` in profile pointed to `$0029/$002A` = `sm_scroll_x/y`
  (camera scroll), NOT party position. Real party tile is at `$0068/$0069`
  = `sm_player_x/y` per Disch FF1 disassembly.
- `InteriorPathfinder` was asking `classifyAt(scroll_origin)` — usually a
  wall/padding tile — instead of `classifyAt(party_tile)`. V2.6.4's static
  `+8/+7` workaround was partial; offset is dynamic.
- `mapflags ($002D)` bit 0 is the canonical "in standard map" flag,
  not `locationType=0xD1` (which only flips for castle/dungeon room).

**Live measurement (V5.6 verification test):**

| Metric | V2.6.5 | V5.6 | DoD |
|---|---|---|---|
| Interior step success rate | 13% | **100%** | >=50% ✓ |
| InteriorMap[8].classifyAt(party) | wall (4,25) scroll | **GRASS** (11,32) sm_player | passable ✓ |
| In-standard-map signal | locType heuristic | **mapflags bit 0** (Disch) | canonical ✓ |
| Town vs Castle | indistinguishable | **`Indoors.isTown`** boolean | ✓ |

105/107 unit tests pass; 2 pre-existing failures unrelated (Coneria8VisualDiffTest,
ConeriaTownEmpiricalDiscoveryTest both fail at WalkOverworldTo, not interior nav).

---

## V5.x commits (2026-05-04)

```
3460174 V5.12 — live integration smoke test for V5.8→V5.10 stack (1/1 ✓)
795c540 V5.11 — forced exploration prompt (GPP "uncover before exit")
0da74db V5.10 — InteriorPathfinder POI priority (memory > viewport > south-edge)
f094106 V5.9  — wire InteriorMemory into ExitInterior + WalkInteriorVision
146e1ad V5.8  — InteriorMemory persistence layer (POI + visited per mapId)
76c4f88 V5.7  — ExitInterior tap precision; mapId=8 is castle evidence
1933668 V5.6  — interior step success 100% empirical proof
c790410 V5.6  — empirical foundation verification on Coneria fixture
79eb87e V5.6  — sm_player_x/y + mapflags replace localX/scroll heuristic
0594723 V5.5  — Disch disassembly identifies canonical addresses
df9b994 V5.4  — FfPhase.Indoors gains isTown discriminator
f8d76f7 V5.3  — RAM-diff identifies $0048 as canonical mapId
```

## V5.8–V5.12 (GPP-inspired InteriorMemory stack)

Adapted from Gemini Plays Pokemon "Map Markers" + "Mental Map" lessons
(jcz.dev). Key components:

- **`InteriorMemory`** (`perception/InteriorMemory.kt`) — JSON file at
  `~/.knes/ff1-interior-memory.json`. Keyed by `(mapId, sm_player_x,
  sm_player_y)`. Five observations with priority order
  `VISITED < POI_DOOR < POI_WARP < POI_STAIRS < EXIT_CONFIRMED`. Higher
  ordinal wins on conflict.
- **`InteriorFrontier`** (`perception/InteriorFrontier.kt`) — BFS helper
  that finds the nearest reachable tile NOT yet recorded as VISITED,
  returning the first-step direction + distance. Used by V5.11 to give
  the vision navigator a "go this way to explore" hint.
- **Wiring**: `ExitInterior` and `WalkInteriorVision` record VISITED on
  every party-tile change, POI_* when classifyAt confirms,
  EXIT_CONFIRMED on `mapflags bit 0 → 0`. `save()` in `finally{}`.
- **Pathfinder priority** (`InteriorPathfinder`): single-pass BFS
  tracks per-category nearest match; returns from highest-priority
  bucket. Without memory, identical to V5.7.
- **Forced-exploration prompt**: `VisionInteriorNavigator.nextDirection`
  takes optional `frontierHint` + `unvisitedReachable`; system prompt
  has a FORCED EXPLORATION rule placing map-cover above exit-seeking.

Live measurement (`V58InteriorMemoryLiveTest`):

| Metric | Value |
|---|---|
| Visited tiles after 20 ExitInterior steps | 16 |
| POIs auto-discovered | `POI_STAIRS@(12,18)` |
| pathfinder w/o memory | 4 SOUTH steps to (10,18) |
| pathfinder w/ POI@(14,17) | 1 NORTH step to (14,17) |
| JSON file | created, roundtrip identical |

---

## What's WORKING (don't break)

- **V5.6 sm_player_x/y wiring** — pathfinder gets real party tile.
  `RamObserver.classify` legacy-fallbacks if profile lacks new keys.
- **V5.6 mapflags-based "in standard map"** — used in `RamObserver`,
  `ExitInterior`, `WalkInteriorVision`, `WalkOverworldTo`.
- **V5.4 `Indoors.isTown`** — discriminator for future town-vs-castle dispatch.
- **V5.7 tap-pattern movement** in ExitInterior — 1 tile per iteration
  (was 3-tile batched with held button). 100% step success preserved.
- **V5.8 InteriorMemory** — persistent JSON; record/get/visited/pois APIs.
  Default constructed in SkillRegistry; tests use temp files.
- **V5.9 wiring** — ExitInterior + WalkInteriorVision record observations
  every step; finally{} save. No behavior change vs V5.7 if memory empty.
- **V5.10 pathfinder priority** — `EXIT_CONFIRMED > POI_* > viewport
  classifier > south-edge`. Verified on live viewport (1 step to POI vs
  4 steps to south-edge fallback).
- **V5.11 forced-exploration prompt** — vision navigator gets frontier
  hint + "uncover map first" rule. Default args = V3.0 prompt
  (backward-compatible).
- **Vision phase classifier (V2.5.0)**, full-map BFS (V2.5.4),
  WalkOverworldTo interior abort (V2.5.2 → V5.6 mapflags), fog defensive
  marking (V2.5.9), PostBattle auto-dismiss (V2.5.6) — all unchanged.
- **`ff1-coneria-interior-discovery.savestate`** — persistent fixture
  inside mapId=8, party at sm_player=(11,32), $48=8, mapflags=1. Saved
  by MapIdDiscoveryTest. Reusable across tests for read-only verification
  (NOTE: input pipeline is broken after `loadState` per V5.2 commit
  comment — `step` produces no movement post-load. Use live boot for
  any test needing taps/movement).

---

## Open blockers (in priority order, post-V5.12)

### 1. POI_STAIRS in castles ≠ exit (NEW, surfaced 2026-05-04 evening)

V5.12 evidence: agent reached STAIRS@(12,18) in mapId=8, A-tapped, NO
mapflags transition. STAIRS in castles navigates between sub-maps
(floors), not to overworld. With V5.10 priority in place, the agent
will keep routing to POI_STAIRS in castles forever, looping between
sub-maps and never reaching overworld.

**Three options** (see `2026-05-04-v512-memory-stack-evidence.md`):
1. Split enum: `POI_SUBMAP_STAIRS` (sighting only) vs `POI_EXIT_STAIRS`
   (only after EXIT_CONFIRMED on this tile in the past). Cleanest.
2. Per-tile boolean `confirmedLeadsOut`. Smallest delta.
3. Track sub-map history via mapId-change-on-step events. Richest data.

Recommended: option (1). Lets V5.10 priority become
`EXIT_CONFIRMED > POI_EXIT_STAIRS > POI_DOOR > POI_WARP > viewport >
south-edge > POI_SUBMAP_STAIRS`. POI_SUBMAP_STAIRS still useful as
"don't re-explore here" hint without misleading the pathfinder.

### 2. Live V5.11 forced-exploration verification

V5.11 prompt path is built (`InteriorFrontier` + frontier hint + system
prompt rule) but never exercised end-to-end with a real vision model.
Costs ANTHROPIC_API_KEY budget. Worth running once after blocker #1 is
fixed, otherwise the agent burns API budget looping in castle stairs.

Test scaffolding to write: V58 variant that boots, walks to mapId=8
interior, runs `WalkInteriorVision` with a real Anthropic navigator,
captures the prompt to verify frontier hint is included, asserts agent
visits at least N unique tiles.

### 3. Find true mapId for Coneria TOWN (deprioritized)

Per GPP research, knowing mapId is less critical now that we have
POI persistence keyed by mapId — the true town mapId becomes just
"another key" once recorded. Still useful for cross-tracking but no
longer blocking.

Method (unchanged): extend `MapIdDiscoveryTest` to walk further on
overworld looking for visible town huts, capture screenshot when vision
says "town", read $48. Likely candidates: small mapIds near 0-5.

### 4. Overworld nav blocked at Coneria area

`findPath (146,158) → (146,150)` returns `found=false` despite full-map BFS.
Hard-impassable rule from V2.5.4 (TOWN/CASTLE blocked) plus terrain.
This is why `Coneria8VisualDiffTest` fails — never reaches interior.

**Investigation hint**: byte 0x26 at (146, 151) is on the path; check
`OverworldTileClassifier.classify(0x26)`. Also look at whether BFS treats
the target tile as passable when it's hard-impassable (V2.5.4 had an
escape clause for "target is itself a TOWN/CASTLE" — verify it fires).
GPP-lesson applies: BFS should return richer "no path" feedback
(closest reachable + frontier of unknowns) instead of bare boolean,
to prevent agent assuming the tool is broken.

---

## Suggested next moves

**No-API-key, deterministic (do these first):**

1. **Phase 3 sweep** to find Coneria Town mapId. Modify `MapIdDiscoveryTest`
   or write new test that walks more directions from spawn, records every
   distinct $48 value seen, and saves screenshots tagged by mapId. ~3 min
   wall, deterministic.

2. **Run V56InteriorPathfindingTest with maxSteps=50** to see if agent
   eventually hits DOOR/STAIRS in mapId=8 castle. The first 20 steps were
   all in row y=32 near south boundary; deeper exploration may find the
   actual castle exit.

3. **Investigate overworld nav blocker**. Modify `OverworldDumpTest`
   (the third disabled test, byte dump x=130-170 y=125-165) to also call
   `OverworldTileClassifier.classify` on the suspect tiles and report
   which bytes classify as hard-impassable along the failing N path.

**Needs API key, live run:**

4. **Live agent run** (Option B from earlier) — `./gradlew :knes-agent:run`.
   Worth it ONLY after blocker #3 is fixed, otherwise agent burns budget
   stuck on overworld.

---

## Anti-patterns to avoid (V2.4 → V5.7 lessons)

1. **No more layers on broken foundation** — V5.6 fix was below all the
   nav layers. If something breaks again, suspect RAM addresses first.
2. **No per-step LLM calls for diagnostic RAM debugging** — V5.3 RAM-diff
   was deterministic and free; vision was unreliable.
3. **STOP after 2 iterations without progress** — V5.7 hit this twice
   (engine batching → south-edge S-append). Re-strategize, don't pile up
   workarounds. V5.7 evidence note documents what didn't work and why.
4. **Don't trust `currentMapId` semantics across map kinds** — $48 holds
   the canonical map id BUT town and castle exit semantics differ.
   Need ROM tile-property table for proper exit handling.

---

## Useful CLI

```bash
# V5.x test suite (no API key, ~30s each)
./gradlew :knes-agent:test --tests "knes.agent.perception.RamObserverTest"
./gradlew :knes-agent:test --tests "knes.agent.perception.V56FoundationVerificationTest"
./gradlew :knes-agent:test --tests "knes.agent.perception.V56InteriorPathfindingTest"
./gradlew :knes-agent:test --tests "knes.agent.perception.MapIdDiscoveryTest"

# Full agent test suite (still expects 2 pre-existing failures)
./gradlew :knes-agent:test

# Live agent run (needs ANTHROPIC_API_KEY)
ANTHROPIC_API_KEY=... \
  KNES_RUN_DIR=$(pwd)/docs/superpowers/runs/<date>-<topic> \
  ./gradlew :knes-agent:run \
    --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes --profile=ff1 \
            --max-skill-invocations=40 --wall-clock-cap-seconds=720"

# Quick trace analysis
python3 -c "
import json
events = [json.loads(l) for l in open('docs/superpowers/runs/.../trace.jsonl')]
for e in events:
    print(e.get('role'), e.get('phase'), e.get('toolCalls', []))"
```

---

## Definition of done for the next milestone

V5.6 foundation is solid (proven empirically). The next deliverable in
roadmap is **agent reaches Coneria interior → exits to overworld → walks
elsewhere** — original V2.6.6 DoD. Concrete sub-goals:

- (a) Identify true mapId for Coneria Town (Phase 3 sweep).
- (b) Make `Coneria8VisualDiffTest` (or its successor for the right mapId)
      pass: agent reaches Coneria Town interior reliably.
- (c) `ExitInterior` reaches overworld in <=20 steps from inside Coneria
      Town fixture.
- (d) Live run confirms party traverses Overworld → Coneria → exit → next
      target without resorting to `pressStartUntilOverworld` reset hack.

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Worktree: `/Users/askowronski/Priv/kNES-ff1-agent-v2` (this branch)
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Test fixtures: `knes-agent/src/test/resources/fixtures/`
  - `ff1-post-boot.savestate` — overworld at (146, 158)
  - `ff1-coneria-interior-discovery.savestate` — inside mapId=8 castle
- Discovery + research notes: `docs/superpowers/notes/2026-05-04-mapid-discovery/`
- Run traces: `~/.knes/runs/<ISO-timestamp>/trace.jsonl`
  (override via `KNES_RUN_DIR`)
- Persistent overworld memory: `~/.knes/ff1-ow-memory.json`

---

## First message to send to the next session

> Resume FF1 Koog agent V5.12 from `HANDOFF.md`. V5.6 foundation
> (sm_player_x/y + mapflags) holds. V5.8→V5.12 added the GPP-inspired
> InteriorMemory stack (POI / VISITED / EXIT_CONFIRMED, persistent JSON,
> POI-priority pathfinder, forced-exploration prompt) — 46/46 unit tests
> green and live evidence in `2026-05-04-v512-memory-stack-evidence.md`.
> Open blockers: (1) POI_STAIRS in castles is sub-map nav, not exit —
> needs enum split (POI_SUBMAP_STAIRS vs POI_EXIT_STAIRS); (2) live V5.11
> verification with real vision navigator (needs API key); (3) Coneria
> Town mapId sweep (now lower priority); (4) overworld BFS blocker.
> **Start with (1)** — read the V5.12 evidence note, pick option 1/2/3
> from there, deterministic & no API key. Conversation in Polish; user
> prefers short iterations, evidence-based conclusions, commits per
> closed phase.
