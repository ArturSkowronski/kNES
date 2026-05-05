# FF1 Koog Agent — Handoff (Phase 1.5 validated end-to-end, 2026-05-05 evening)

**Master HEAD:** `c250d5b` — PR #104 merged (3 PRs this session: #102 + #103 + #104)
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — Phase 1.5 closed, agent enters interiors and Haiku classifies NPCs

Today shipped 3 PRs that close the loop on cross-run explorer Phase 1.5/2.

```
c250d5b  PR #104 merge: traversal fix (impassable purge + priority 4/5 recentlyFailed)
a604e72  PR #103 merge: per-run novelty for NewInteriorEntered
f13bd98  PR #102 merge: Phase 1.5 real Haiku + Phase 2 LandmarkContext + salience tier
```

**Live evidence — 7-run campaign with cleared landmarks file:**

```
[campaign] FINAL: CampaignResult(
  outcome=Plateau, runsExecuted=7,
  coverage=CoverageStats(terrainTilesKnown=65502, warpsKnown=4,
    landmarksKnown=13, landmarksVisited=1, interiorMapsExplored=3),
  totalCostUsd=8.98E-4)
```

- ✓ `interior_entry_8_145_152` recorded with `mapIdInterior=8, visited=true` —
  agent entered via warp at (145,152), the first confirmed entry written by
  the explorer pipeline.
- ✓ Haiku 4.5 called for real, cost \$0.0009 (~700 input + 30 output tokens).
- ✓ NPC classifications written (all `discoveredRunId=run-2`):
  - `NPC_KING` — "Crowned figure on throne in upper chamber"
  - `NPC_GENERIC` — "Guard or attendant on left side of throne room"
  - `STAIRS_DOWN` — "Descending staircase in center of lower chamber"

---

## Bugs fixed this session (in order)

### PR #102 (`f13bd98`) — already documented in prior HANDOFF

Phase 1.5 (real Haiku 4.5 over HTTP), Phase 2 (`AgentSession` reads
`~/.knes/ff1-landmarks.json`), salience priority 1A/1B split + `LandmarkMemory.recordIfNew` upgrade-on-dup.

### PR #103 (`a604e72`) — per-run novelty for NewInteriorEntered

`SingleRun.detectTrigger` gated on `!interiorMemory.hasMapBeenSeen(mapId)`,
persistent across sessions. With 3 pre-seeded maps in
`~/.knes/ff1-interior-memory.json`, the trigger never fired and Haiku stayed
at \$0 every campaign.

Fix: track `novelMapIdsThisRun: MutableSet<Int>` in `SingleRun`. Trigger fires
the first time agent enters each map THIS run regardless of historic memory;
subsequent steps in the same run for the same map don't re-trigger. Bounded
by ~3-5 maps per run.

`detectTrigger` lifted to `SingleRun.companion` (pure function) for direct
unit testing — no need to spin up the full SingleRun.

### PR #104 (`c250d5b`) — overworld traversal unblocked

Two correlated fixes:

**1. `f2d899c` — purge tile-tagged landmark on impassable / no-path.**
Live evidence after #103: 228× "stuck at (146,158): no path within viewport"
+ 20× "target (146,150) is impassable terrain". `SalienceStrategy` priority
2 records every TOWN/CASTLE-typed viewport tile as a candidate Landmark, but
`OverworldTileClassifier` conflates entry tiles with wall decoration (same
misclassification documented in pre-existing `Coneria8VisualDiffTest`).
Priority 1B then picks the bogus landmark, BFS rejects it, blockage logged,
same landmark re-picked next iteration.

Fix: when pathfinder reports impassable / no-path-within-viewport, drop the
tile-tagged landmark at the target coord via new
`LandmarkMemory.removeTileTaggedAt(worldX, worldY)`. Confirmed entries
(`mapIdInterior != null`) preserved.

**2. `257d3c6` — priorities 4 + 5 must filter recentlyFailed.**
Even after the purge, live still showed (146,150) re-targeted 10× in one run.
Trace: salience priority 5 (wander) does row-major viewport scan, returns
first `isPassable()` tile — deterministically the same impassable-per-BFS
tile every iteration. Priority 4 (cardinal diversify) had a parallel gap:
filtered against `pathTriedRecentDirections` but not against the resulting
candidate coord.

Fix: add `asKey(candidate) !in recentlyFailed` to both priorities. Priority 5
also explicitly skips currentXY (was relying on `isPassable` returning false
for party tile, which isn't guaranteed).

---

## Read first (next session, in this order)

1. **This file**, then `git log --oneline -10 origin/master`.
2. `~/.knes/ff1-landmarks.json` — currently 120 stale tile-tagged records
   (backup at `.before-fix-2026-05-05`). Live-run state was 13 landmarks
   (5 town + 5 castle + 1 NPC_KING + 1 NPC_GENERIC + 1 STAIRS_DOWN) before
   restore.
3. `knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt` —
   priority chain 1A/1B/2/3/4/5; all priorities now respect `recentlyFailed`.
4. `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt:detectTrigger` —
   companion-function form, gated on per-run `novelMapIdsThisRun`. Also
   `looksUnreachable` companion + `removeTileTaggedAt` call after blockage.
5. `knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt` —
   real HTTP path; `parseInteriorResponse` + `parseDialogResponse` on companion.

---

## Top priorities for next session

### A. mapId mismatch in Haiku classification

The single confirmed entry was at world (145,152) which is a Coneria warp
to mapId=8 (Coneria Town). But Haiku's classification described a "throne
room with crowned figure" — that's Coneria Castle (mapId=1), not Coneria
Town. Two possibilities:

1. The phase classifier (`RamObserver.observeWithVision`) returned
   `Indoors(mapId=8)` when the agent was actually inside Castle (mapId=1).
2. Haiku misread the screenshot. Less likely — its description is too
   specific (king + guard + throne).

Diagnostic: capture a screenshot at the moment of `handleNewInterior` and
correlate with `currentMapId` RAM byte. If the RAM byte disagrees with the
phase classifier's claim, fix the classifier.

### B. Phase 2 not yet validated live

`AgentSession` reads `landmarks.json` at startup but the `LandmarkContext`
injection's effect on agent behavior (does it route to known towns? does
the Garland stuck-pattern improve?) is unverified. Run
`./gradlew :knes-agent:runAgent` with the populated landmarks file from
the explorer campaign and observe the advisor/executor traces.

### C. Long campaign at low cost

Per-run cost is ~\$0.0009 with current Haiku usage. Running 30+ runs would
cost ~\$0.03 and cover much more of the world. Worth a try with
`maxRuns=50, maxTotalCostUsd=2.0`.

### D. UnknownMapTrap recovery

Run-3 of validation campaign hit `stop=UnknownMapTrap` (mapId=0 with
mapflags=1, 3+ idle turns). Currently the run just exits. Could try
`pressStartUntilOverworld` to recover within the run — but V5.31 panic-reset
guard already restricts that to TitleOrMenu phase, so we'd need a different
escape strategy.

## Open work / tech debt

1. **Memory files non-atomic save.** All persistent memories use
   `writeText()` directly — crash mid-save corrupts. Need write-temp-then-rename.
2. **Explorer doesn't accumulate warps cross-run.** Warps detected by
   AgentSession persist; explorer's discoveries don't (it doesn't write to
   `OverworldWarpMemory`). Run-2's entry via (145,152) WAS already in warp
   memory from prior AgentSession runs, which is why traversal worked.
3. **`isDialogBoxOpen` returns false.** `Trigger.DialogBoxVisible` never
   fires. Need a real RAM signature or screenshot-diff heuristic.
4. **Anthropic prompt caching still no-op.** Koog 0.6.x lacks
   `cache_control` field. The explorer's HaikuConsult HTTP path is direct
   and could add it; not yet wired.

---

## Test status

```
./gradlew :knes-agent:test
```

**205 pass / 2 pre-existing failures / 7 skipped** (master HEAD c250d5b).

Pre-existing failures: `Coneria8VisualDiffTest`, `ConeriaTownEmpiricalDiscoveryTest`.

```
./gradlew :knes-agent:test --tests "*Live*"   # opt-in, needs ANTHROPIC_API_KEY
```

---

## Useful CLI

```bash
# Build memory cheaply (Haiku, no Opus). Default 20 runs / \$1 cap.
./gradlew :knes-agent:runExplorer

# Existing agent. Reads landmarks.json on startup (Phase 2).
./gradlew :knes-agent:runAgent

# Tests
./gradlew :knes-agent:test                                   # full suite
./gradlew :knes-agent:test --tests "*Live*"                  # opt-in live
./gradlew :knes-agent:test --tests "*AnthropicHaikuConsult*" # parsing only
./gradlew :knes-agent:test --tests "*SalienceStrategy*"      # priorities
./gradlew :knes-agent:test --tests "*DetectTrigger*"         # gating

# Inspect live memory
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq '[.landmarks | group_by(.kind) | .[] | {k: .[0].kind, n: length, confirmed: (map(select(.mapIdInterior != null)) | length)}]' ~/.knes/ff1-landmarks.json
jq '.blockages | length, [.blockages | group_by(.attemptedTo) | sort_by(-(.|length)) | .[0:5] | .[] | {tgt: .[0].attemptedTo, n: length}]' ~/.knes/ff1-blockages.json

# Reset landmarks for a clean validation run (keep the backup)
jq '{landmarks: []}' /dev/null > /tmp/empty.json && cp /tmp/empty.json ~/.knes/ff1-landmarks.json
```

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Worktree (last branch `ff1-explorer-overworld-traversal`, now identical to master): `/Users/askowronski/Priv/kNES-ff1-agent-v2`
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent memory: `~/.knes/ff1-{overworld-terrain,landmarks,blockages,overworld-warps,interior-memory}.json`
- Pre-fix backup: `~/.knes/ff1-landmarks.json.before-fix-2026-05-05` (120 stale tile-tags)
- Run traces: `~/.knes/runs/<ISO-timestamp>/trace.jsonl` (override `KNES_RUN_DIR`)

## Test fixtures

- `ff1-post-boot.savestate` — overworld at (146, 158)
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8

---

## First message to send to next session (suggestion)

> Master at `c250d5b`. Three PRs merged this session (#102, #103, #104) closing
> Phase 1.5/2 + traversal. End-to-end validated live: 1 confirmed entry into
> mapId=8 via warp (145,152), Haiku called for real (\$0.0009), 3 NPC landmarks
> classified (KING + GENERIC + STAIRS_DOWN). Top priorities: (A) diagnose mapId
> mismatch — Haiku described throne room but mapId reported was 8 (Coneria Town,
> no throne); likely phase classifier confusion between Castle (1) and Town (8).
> (B) Validate Phase 2 live — run runAgent with populated landmarks.json and
> watch advisor/executor traces. (C) Long low-cost campaign (50 runs, \$2 cap).
> Conversation in Polish; PR-flow + tests-first + commit per closed phase.
