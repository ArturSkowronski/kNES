# FF1 Koog Agent — Handoff (Phases 1.5 + 2 verified live, 2026-05-06 morning)

**Master HEAD:** `bf00bed` — PR #110 merged. **Five PRs this session: #106 + #107 + #108 + #109 + #110** (#105 closed unmerged).
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — both target phases verified end-to-end with live API runs

```
bf00bed  PR #110 merge: filter mapId=0 in auto-detected warps
ab0b677  PR #109 merge: post-loadState warm-up — bridges V5.2 input-frame drop
30fb464  PR #107 merge: salience priority 0 — target known warp tiles
7be39be  PR #108 merge: priority 0 must not filter recentlyFailed
fc25534  PR #106 merge: tag landmarks with live mapId; skip Haiku on wipe / overworld
```

Phase 1.5 mapId mismatch root cause and Phase 2 LandmarkContext injection both have direct live evidence in trace files.

---

## What was wrong (root cause from the diagnostic in #105)

The morning campaign reported `interior_entry_8_145_152` (mapIdInterior=8, kind=TOWN_ENTRY) and Haiku then described "throne room with crowned figure" — inconsistent with mapId=8 being Coneria Town. Two co-occurring bugs in `handleNewInterior`:

### A. Stale `t.mapId` tag (#106)
`Trigger.NewInteriorEntered.mapId` captures the moment phase becomes `Indoors`. FF1 multi-stage warps briefly expose a transient `currentMapId`: `(145,152)` Coneria warp → `mapId=8` outer overlay → `mapId=24` inner. By the first `ramSnapshot()` inside `handleNewInterior` the value has stabilised; entry landmark + Haiku call now both use the live RAM mapId.

### B. PartyWipe / overworld-return corrupts Haiku call (#106)
A wipe inside `exploreInteriorFrontier` returns the player to the title screen; `getScreen()` then captures that frame. Haiku misclassifies UI text — observed: `NPC_GENERIC` recorded with note _"Person sprite next to NEW GAME text"_, tagged mapId=8. New `decideClassification` companion gates the Haiku call on `mapflags=1 && hpSum>0`.

### C. Explorer plateau at 0 entries
- **#107**: `OverworldWarpMemory` carries cross-session warp evidence but salience never used it. Priority 0 in `pickOverworldTarget` now picks the closest unvisited-this-run warp.
- **#108**: `BlockageMemory.recentlyFailedTargets` 10-min global window poisoned warps after the V5.2 first-attempt failure. Priority 0 now filters only by per-run `enteredWarpsThisRun`.
- **#109**: `WalkOverworldTo`'s `INPUT_DEAD_THRESHOLD=3` aborted on every first walk because the emulator drops input frames after `loadState`. New `WARMUP_FRAMES=30` NOOP step in `ExplorerSession.run` after each `loadState` bridges the threshold.

### D. UnknownMapTrap recorded as warp (#110)
`AgentSession.failedRegex` matched `world=(X,Y)...targeted=false` from `WalkOverworldTo.aborted` but ignored the `mapId=N` field. Engine void state (mapId=0 + mapflags=1) got recorded as a warp; priority-0 then dead-ended every overworld run there. Regex now captures mapId; `mapId == 0` → log + skip, no warp memory entry. Captured mapId is also passed to `warpMemory.record(...)` so future entries have the actual interior mapId.

---

## Live verification status — all green

| Bug | Verified live? | Evidence |
|---|---|---|
| **A — live mapId tag** | ✅ | Cleaned-warps overworld campaign produced `interior_entry_8_146_152` + 7× `haiku_npc_king_24` (mapId=24, throne notes). Pre-fix the same scenario tagged throne with mapId=8. |
| **B — PartyWipe gate** | ✅ | Interior-fixture campaign run-1 stop=PartyWiped → cost=$0.0 (was $0.000578 pre-fix), only entry landmark recorded, zero "NEW GAME" garbage. |
| **C — explorer reaches warps** | ✅ | First overworld campaign post-#109 produced 11 runs, every one entered an interior, $0.005 total cost. First explorer success in this whole investigation. |
| **D — mapId=0 not auto-detected** | ✅ unit + indirect | 5 regex tests pass; live no longer needed since the cleanup removed the artefact. Future auto-detects will write mapId correctly. |
| **Phase 2 LandmarkContext** | ✅ | `runAgent` trace (`~/.knes/runs/2026-05-06T05-36-14.521274Z/trace.jsonl`) shows the rendered landmark block in advisor input AND advisor output references it: cites `(146, 152)` TOWN_ENTRY, describes "castle with King/throne room and stairs down" pulled verbatim from Haiku notes, plans routes around (145, 152) warp. |

---

## Open work / known issues

### UnknownMapTrap is still a real problem
Every overworld explorer run currently ends `stop=UnknownMapTrap`. The trap fires when `mapflags=1 && mapId !in knownMapIds && consecutiveIdleInTrap >= 3`. Agent enters mapId=8 → walks stairs at (12,18) → mapId=24 → from mapId=24's stairs (or another transition), winds up at mapId=0 (engine void). Three idle frames there → trap → run ends.

V5.31 panic-reset guard restricts `pressStartUntilOverworld` to TitleOrMenu only; we'd need a different escape strategy:
1. Allow `pressStartUntilOverworld` from mapId=0 + mapflags=1 (degenerate state, can't easily make worse).
2. Hard-reset via `emulatorSession.reset()` — explorer reloads savestate per run anyway, so acceptable.
3. Add `mapId=0 + mapflags=1` to `knownMapIds` with a custom escape skill.

### Coverage instrumentation
`terrainTilesKnown` delta=0 every run — the savestate already has full overworld scan. The current "plateau" heuristic doesn't capture meaningful progress. Need a metric based on:
- Confirmed-entries count (`landmarks where mapIdInterior != null`).
- Distinct-mapIds-classified count.
- Or just visited-landmarks count.

### Auto-detect mapId capture still needs PR #110 to actually run
The mapId capture wiring in PR #110 fires when AgentSession (not ExplorerSession) detects an UNEXPECTED interior. To populate warp memory with correct mapIds we need a `runAgent` campaign — which we did during Phase 2 verification but it crashed via the same UnknownMapTrap from (144,153). Real population happens once UnknownMapTrap recovery exists.

### Pre-#110 entries in `~/.knes/ff1-overworld-warps.json` with mapId=0
Ambiguous (real warp pre-fix vs UnknownMapTrap pre-fix). Manual cleanup if needed; the live-verified entry `(145,152)` is annotated; everything else may need re-discovery.

---

## Top priorities for next session

### A. UnknownMapTrap recovery
Highest leverage — every overworld campaign currently ends in trap, capping per-run yield at one classification. Implement option 2 (hard reset via `emulatorSession.reset()` + reload savestate within the same run) or option 1 (relax V5.31 guard for mapId=0).

### B. Atomic memory file saves
All persistent JSON memories use `writeText()` directly. Crash mid-save corrupts. Switch to write-temp-then-rename pattern. Low complexity, high robustness payoff.

### C. Coverage metric improvement
Replace `terrainTilesKnown` delta with `visited-landmarks` delta in the campaign plateau heuristic.

### D. Full Garland campaign
With Phase 1.5 + Phase 2 closed and #109's warm-up landing entries reliably, a long campaign with `maxRuns=50, maxTotalCostUsd=2.0` is now meaningful — would map more of FF1's overworld and populate more landmarks for downstream advisor reasoning. Blocked on (A) — without trap recovery, every run wastes 80% of its budget on the failed escape.

---

## Code architecture (post-#110)

```
knes-agent/src/main/kotlin/knes/agent/
  explorer/
    ExplorerSession.kt
      run() now does:
        loadState
        toolset.step(buttons=[], frames=WARMUP_FRAMES=30)   // bridges V5.2 quirk
        SingleRun(...).execute()
    SingleRun.kt
      handleNewInterior(): returns Int? (effective mapId or null on skip);
                           tracks enteredWarpsThisRun
      Companion:
        decideClassification(triggerMapId, ramAfter): ClassifyDecision?
          - null on hpSum=0 (PartyWipe) or mapflags!=1 (back overworld)
          - mapIdToUse = ram["currentMapId"] or trigger fallback
        applyInteriorClassification, detectTrigger, looksUnreachable, checkRestart
    SalienceStrategy.kt
      pickOverworldTarget(currentXY, viewport, enteredWarpsThisRun)
        Priority 0: closest known warp not yet entered this run
                    (no recentlyFailed filter — see #108)
        Priority 1A: confirmed entries (mapIdInterior set)
        Priority 1B: tile-tagged candidates within distance limit
        Priority 2-5: unchanged
  runtime/
    AgentSession.kt
      Companion: FAILED_WARP_REGEX captures (worldX, worldY, mapId)
      Auto-detect skips mapId=0 transitions (UnknownMapTrap not a real warp)
      Pre-loaded LandmarkContext.render(landmarkMemory) injected into both
      advisor + executor observations on every phase change.
    LandmarkContext.kt — unchanged; renders LandmarkMemory grouped by kind.
```

---

## Read-first files (next session, in this order)

1. **This file** + `git log --oneline -8 origin/master`.
2. `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt:230-235` — UnknownMapTrap detection in `checkRestart`-like logic; design escape from mapId=0.
3. `knes-agent/src/main/kotlin/knes/agent/skills/PressStartUntilOverworld.kt` — V5.31 guard limiting it to TitleOrMenu; consider relaxing for mapId=0 case.
4. Memory file save call sites: `LandmarkMemory.save`, `BlockageMemory.save`, `OverworldWarpMemory.save`, `InteriorMemory.save`, `OverworldTerrainMemory.save` — atomicity audit.

---

## Test status

```
./gradlew :knes-agent:test
```

**218 pass / 2 pre-existing fail / 7 skipped** (master HEAD `bf00bed`).

Pre-existing failures: `Coneria8VisualDiffTest`, `ConeriaTownEmpiricalDiscoveryTest`.

```
./gradlew :knes-agent:test --tests "*Live*"            # opt-in, needs ANTHROPIC_API_KEY
./gradlew :knes-agent:test --tests "*FailedWarpRegex*" # PR #110 regex
./gradlew :knes-agent:test --tests "*SalienceStrategy*" # priorities incl. 0
./gradlew :knes-agent:test --tests "*HandleNewInterior*" # decideClassification
```

---

## Useful CLI

```bash
# Cheap explorer build (Haiku only). Default 20 runs / $1 cap.
./gradlew :knes-agent:runExplorer

# Override savestate (interior fixture starts already inside Coneria Town):
FF1_SAVESTATE=knes-agent/src/test/resources/fixtures/ff1-coneria-interior-discovery.savestate \
  ./gradlew :knes-agent:runExplorer

# Existing AgentSession (Phase 2 reads landmarks.json + uses LandmarkContext):
./gradlew :knes-agent:run --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes \
  --max-skill-invocations=8 --cost-cap-usd=0.5 --wall-clock-cap-seconds=120"

# Inspect memory
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq '[.landmarks | group_by(.kind)[] | {k: .[0].kind, n: length, mapIds: ([.[].mapId, .[].mapIdInterior] | map(select(. != null)) | unique)}]' ~/.knes/ff1-landmarks.json
jq '.tiles' ~/.knes/ff1-overworld-warps.json
jq -r 'select(.role == "advisor") | .input' ~/.knes/runs/<latest>/trace.jsonl | head -100
```

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Worktree: `/Users/askowronski/Priv/kNES-ff1-agent-v2`
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent memory: `~/.knes/ff1-{overworld-terrain,landmarks,blockages,overworld-warps,interior-memory}.json`
- Run traces: `~/.knes/runs/<ISO-timestamp>/trace.jsonl`
- Archives:
  - `~/.knes/archive-2026-05-05-pre-mapid-fix/` (pre-#106)
  - `~/.knes/archive-2026-05-05-pre-warp-targeting/` (pre-#107/#108)
  - `~/.knes/archive-2026-05-05-pre-warmup/` (pre-#109; includes pre-cleanup warps)

## Test fixtures
- `ff1-post-boot.savestate` — overworld at (146, 158)
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8 at party=(11, 32)

---

## First message to send to next session (suggestion)

> Master at `bf00bed`. Five PRs merged this session (#106 + #107 + #108 + #109 + #110); #105 closed unmerged. Phase 1.5 + Phase 2 both verified live: tonight's overworld campaign produces correct multi-stage warp tagging (TOWN_ENTRY mapId=8 entry at (146,152), NPC_KING mapId=24 from castle), and `runAgent` trace confirms the advisor reads + reasons over `LandmarkContext` (cites (146,152) coords, references "castle with King/throne room and stairs down" verbatim from Haiku notes). Top priority: **UnknownMapTrap recovery** (every overworld run currently ends in mapId=0 trap from castle stairs — caps per-run Haiku yield at 1 and starves long campaigns). Then atomic memory saves + improved coverage metric + full Garland campaign. Conversation in Polish; PR-flow + tests-first + commit per closed phase.
