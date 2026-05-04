# FF1 Koog Agent — Handoff (V5.7 → next)

**Branch:** `ff1-agent-v2-4` of `/Users/askowronski/Priv/kNES-ff1-agent-v2`
**HEAD:** `76c4f88` — V5.7 ExitInterior tap-pattern + mapId=8-is-castle evidence
**Required env:** `ANTHROPIC_API_KEY` (only for live agent runs; not for tests below)

---

## Read first (in this exact order)

1. `docs/superpowers/notes/2026-05-04-mapid-discovery/report.md` — V5.3 RAM-diff
   findings, candidate map-id bytes, all evidence from the discovery probe.
2. `docs/superpowers/notes/2026-05-04-mapid-discovery/fix-b-research.md` — V5.5
   Disch FF1 disassembly cross-check; canonical addresses; 3-phase fix proposal.
3. `docs/superpowers/notes/2026-05-04-mapid-discovery/v57-mapid8-is-castle.md` —
   V5.7 evidence that mapId=8 is Coneria Castle, not Coneria Town.
4. `knes-debug/src/main/resources/profiles/ff1.json` — V5.6 profile (mapflags,
   smPlayerX/Y, sm_scroll, curTileset, facing, vehicle).
5. `knes-agent/src/main/kotlin/knes/agent/perception/RamObserver.kt` — classify()
   dispatches on mapflags; Indoors party from sm_player.
6. `knes-agent/src/test/kotlin/knes/agent/perception/MapIdDiscoveryTest.kt`,
   `V56FoundationVerificationTest.kt`, `V56InteriorPathfindingTest.kt` — V5.x
   test suite (all live boot, no API key needed).

---

## TL;DR — where we are (2026-05-04)

V2.4 → V2.6.5 had 5 sessions of nav stuckness with 13% interior step success
rate. **Today's V5.x stack identified the foundation bug and fixed it**:

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

## V5.x commits (today, 2026-05-04)

```
76c4f88 V5.7 — ExitInterior tap precision; mapId=8 is castle evidence
1933668 V5.6 — interior step success 100% empirical proof
c790410 V5.6 — empirical foundation verification on Coneria fixture
79eb87e V5.6 — sm_player_x/y + mapflags replace localX/scroll heuristic
0594723 V5.5 — Disch disassembly identifies canonical addresses
df9b994 V5.4 — FfPhase.Indoors gains isTown discriminator
f8d76f7 V5.3 — RAM-diff identifies $0048 as canonical mapId
```

---

## What's WORKING (don't break)

- **V5.6 sm_player_x/y wiring** — pathfinder gets real party tile.
  `RamObserver.classify` legacy-fallbacks if profile lacks new keys.
- **V5.6 mapflags-based "in standard map"** — used in `RamObserver`,
  `ExitInterior`, `WalkInteriorVision`, `WalkOverworldTo`.
- **V5.4 `Indoors.isTown`** — discriminator for future town-vs-castle dispatch.
- **V5.7 tap-pattern movement** in ExitInterior — 1 tile per iteration
  (was 3-tile batched with held button). 100% step success preserved.
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

## Open blockers (deferred, in priority order)

### 1. Find true mapId for Coneria TOWN (Phase 3)

Three independent signals confirm `mapId=8` is **Coneria Castle**, not Town:
- Decoded ROM data: walls 0x30 + floors 0x31 + STAIRS 0x44 (castle pattern)
- V2.6.x vision-trace: "Castle courtyard, not town huts"
- V5.7: engine refused 18× SOUTH-tap from heuristic south-edge tile (9,32)

We don't yet know which `$48` value corresponds to actual Coneria Town
(town-outdoor map with NPCs walking, no random encounters).

**Method**: extend `MapIdDiscoveryTest` to walk further on overworld looking
for visible town huts, capture screenshot when vision says "town", read $48.
Likely candidates: small mapIds near 0-5 (Coneria area maps cluster).

### 2. Castle exit logic (DOOR/STAIRS detection)

`ExitInterior` + `InteriorPathfinder` find south-edge "exits" but engine
doesn't transition there for castles. Castles use explicit `DOOR` / `STAIRS`
tile types. The decoder already classifies `0x44 → STAIRS`, but
`InteriorPathfinder` treats all of DOOR/STAIRS/WARP equally and `ExitInterior`
A-taps after stepping onto them — that part should work for castles when
the agent reaches a STAIRS tile. Worth verifying with a deeper run
(maxSteps=50+) on mapId=8 fixture.

### 3. Overworld nav blocked at Coneria area

`findPath (146,158) → (146,150)` returns `found=false` despite full-map BFS.
Hard-impassable rule from V2.5.4 (TOWN/CASTLE blocked) plus terrain.
This is why `Coneria8VisualDiffTest` fails — never reaches interior.

**Investigation hint**: byte 0x26 at (146, 151) is on the path; check
`OverworldTileClassifier.classify(0x26)`. Also look at whether BFS treats
the target tile as passable when it's hard-impassable (V2.5.4 had an
escape clause for "target is itself a TOWN/CASTLE" — verify it fires).

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

> Resume FF1 Koog agent V5.7 from `HANDOFF.md`. Read the doc + linked files
> in order (especially the three v5.x notes). Foundation V5.6 (sm_player_x/y
> + mapflags) is verified empirically — 13% → 100% interior step success.
> Open: (1) find true Coneria Town mapId via overworld sweep, (2) verify
> castle DOOR/STAIRS exit in mapId=8 with maxSteps=50, (3) overworld BFS
> blocker at Coneria. Start with (1) — extend MapIdDiscoveryTest, no API
> key needed. Conversation in Polish; user prefers short iterations,
> evidence-based conclusions, commits per closed phase.
