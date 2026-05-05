# FF1 Koog Agent — Handoff (Phase 1.5 fully verified live, 2026-05-05 night)

**Master HEAD:** `ab0b677` — PR #109 merged. **Four PRs this session: #106 + #107 + #108 + #109** (#105 closed unmerged).
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — Phase 1.5 mapId mismatch closed; both bugs verified live

The investigation that #105 (diagnostic, closed unmerged) opened is now fully closed. Multi-stage warp at (146, 152) → mapId=8 (overlay) → mapId=24 (Coneria Castle) is correctly handled.

```
ab0b677  PR #109 merge: post-loadState warm-up — bridges V5.2 input-frame drop
30fb464  PR #107 merge: salience priority 0 — target known warp tiles deliberately
7be39be  PR #108 merge: priority 0 must not filter recentlyFailed (V5.2 quirk)
fc25534  PR #106 merge: tag landmarks with live mapId; skip Haiku on wipe / overworld
```

(Also `a852f23` mid-session handoff, then `ab0b677` final.)

### Live evidence — overworld campaign post-warmup, cleaned warp memory

11-run campaign with cleared landmarks/blockages/interior, only the manually-annotated `(145, 152)` warp:

```
landmarks final state:
- 7× NPC_KING       mapId=24 — "Crowned figure on throne in center of [room|chamber]"
- 1× STAIRS_DOWN    mapId=24 — "Staircase descending..."
- 1× TOWN_ENTRY     mapIdInterior=8  worldXY=(146,152)  visited=true
totalCostUsd ≈ $0.005, runs 2-11 entered interior, every run hit UnknownMapTrap exit
```

This is the morning campaign's claim made true:
- Entry tile recorded with **live RAM mapId at entry moment** (= 8, Coneria Town overlay).
- Haiku post-explore screenshot caught the Castle (the agent walked through stairs at mapId=8 (12,18) into mapId=24 during the 80-step explore).
- Haiku landmarks tagged with **live RAM mapId at screenshot moment** (= 24, NOT the trigger's stale 8).

Pre-fix morning campaign: throne was tagged `mapId=8` (wrong). Post-fix tonight: throne tagged `mapId=24` (right).

---

## Bugs fixed this session (recap)

### A. Stale `t.mapId` tag on landmarks (PR #106) — verified live ✅
`Trigger.NewInteriorEntered.mapId` captures the moment phase becomes `Indoors`. FF1 multi-stage warps briefly expose a transient `currentMapId` during the engine handoff. By the first `ramSnapshot()` inside `handleNewInterior` the value has stabilised. Both the entry landmark and the Haiku call now use the live RAM mapId, falling back to `t.mapId` only if RAM is unreadable. `handleNewInterior` returns the effective mapId so the caller adds **both** trigger and stabilised mapIds to `novelMapIdsThisRun`.

### B. PartyWipe / overworld-return corrupts Haiku call (PR #106) — verified live ✅
A wipe inside `exploreInteriorFrontier` returns the player to the title screen; `getScreen()` then captures that frame and Haiku misclassifies UI text. New `decideClassification` companion-helper gates the Haiku call on `mapflags=1 && hpSum > 0`. **Live evidence (interior fixture campaign):** run-1 stop=PartyWiped → cost=$0.0 (was $0.000578 pre-fix), only the entry landmark recorded, zero "NEW GAME" garbage.

### C. Explorer plateau at 0 entries (PRs #107 + #108 + #109) — verified live ✅
Three independent issues in the deterministic explorer:

1. **#107 — salience priority 0 (known warps).** `OverworldWarpMemory` carries cross-session evidence that specific tiles trigger interiors, but salience never used it. Now explicit priority 0 in `pickOverworldTarget` picks the closest unvisited-this-run warp before any other heuristic.
2. **#108 — priority 0 doesn't filter recentlyFailed.** `BlockageMemory.recentlyFailedTargets` has a 10-min global window. The first warp attempt typically failed (V5.2 quirk below), poisoning the warp for the next 10 minutes across runs. Per-run `enteredWarpsThisRun` is the right gate.
3. **#109 — post-loadState warm-up.** `WalkOverworldTo`'s `INPUT_DEAD_THRESHOLD=3` aborted with "input not responding" on every first walk because the emulator drops input frames after `loadState`. New `WARMUP_FRAMES=30` NOOP step in `ExplorerSession.run` after each `loadState` bridges the threshold.

---

## Open work / known issues

### Bogus auto-detected warps in `OverworldWarpMemory`
Pre-cleanup, `~/.knes/ff1-overworld-warps.json` contained four entries: `(145,152)` (manual annotation, real Coneria warp) plus `(144,153)`, `(147,153)`, `(147,154)` auto-detected. Two of those auto-detected are real (per FF1 ROM), but `(147,154)` triggers a transition to `mapId=0 + mapflags=1` — UnknownMapTrap, not a real interior. Auto-detection in `AgentSession.kt` records every "UNEXPECTED interior entry" without filtering on `mapId != 0`. Cleanup fix: extend the regex (or post-filter) to skip mapId=0.

For tonight's verification I manually pruned the warp file to only `(145,152)` so priority 0 would target the real Coneria warp.

### UnknownMapTrap exits every overworld run
Run-2..11 of tonight's campaign each ended `stop=UnknownMapTrap`. The trap fires when `mapflags=1 && mapId !in knownMapIds && consecutiveIdleInTrap >= 3`. The agent enters mapId=8 → walks stairs → mapId=24 → from mapId=24's stairs, transitions to mapId=0 (void, no map data loaded). Three idle frames in mapId=0 → UnknownMapTrap → run ends. This is a real engine state (FF1 doesn't have a defined behaviour for stepping past Castle stairs in our context); we just need a recovery strategy.

The original Priority D from the prior HANDOFF: V5.31 panic-reset guard restricts `pressStartUntilOverworld` to TitleOrMenu phase; it'd need to also handle "mapflags=1 + mapId=0" or a different escape path.

### Phase 2 LandmarkContext live validation (was Priority B, still untouched)
`AgentSession` reads `landmarks.json` at startup, but the `LandmarkContext` injection's effect on agent behaviour is unverified. With tonight's known-good landmarks data populated (7× NPC_KING@mapId=24, 1× STAIRS_DOWN, 1× TOWN_ENTRY), this is a good time to run `runAgent` and watch advisor/executor traces.

---

## Top priorities for next session

### A. Fix bogus auto-detected warps
In `AgentSession.kt:194`, the `failedRegex` extracts `world=(X,Y)` from the toolCallLog `walkOverworldTo.aborted` message. The same message includes `mapId=N`. Extend the regex (or split parsing) to also capture mapId; skip recording when mapId == 0. Optionally also add a one-shot cleanup pass on file load to drop existing mapId=0 records.

### B. Phase 2 LandmarkContext live validation
Run `./gradlew :knes-agent:runAgent` with the populated landmarks file. Observe whether the advisor's prompt actually uses `LandmarkContext`-injected entries (check the trace's advisor input). If yes → Phase 2 closed. If not → debug LandmarkContext wiring.

### C. UnknownMapTrap recovery strategy
With every overworld run ending in mapId=0 trap, the explorer's per-run yield is capped at one classification per run. Recovery options:
1. `pressStartUntilOverworld` from inside the trap (might work since mapId=0 is degenerate state).
2. Hard-reset the run via emulator `reset()`. Side effect: loses non-savestate progress, but since explorer reloads savestate per run anyway, acceptable.
3. Add `mapId=0 + mapflags=1` to `knownMapIds` so the trap doesn't trigger; let exploreInteriorFrontier figure out an exit.

### D. Coverage instrumentation
Tonight's campaign had `terrainTilesKnown=0` delta every run because the savestate already contains a full overworld scan. New territory only opens when warp interiors lead somewhere new. Consider a different progress metric — confirmed-entries count, distinct-mapIds-classified count, etc.

---

## Code architecture (post-#109)

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
                           tracks enteredWarpsThisRun (entered (cx,cy) before recording)
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
```

---

## Read-first files (next session, in this order)

1. **This file** + `git log --oneline -8 origin/master`.
2. `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt:194` — `failedRegex`; extend to filter mapId=0.
3. `knes-agent/src/main/kotlin/knes/agent/perception/OverworldWarpMemory.kt` — consider on-load cleanup of mapId=0 records.
4. `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt:117` (handleNewInterior) — verify mapId tagging matches expectations.

---

## Test status

```
./gradlew :knes-agent:test
```

**213 pass / 2 pre-existing fail / 7 skipped** (master HEAD `ab0b677`).

Pre-existing failures: `Coneria8VisualDiffTest`, `ConeriaTownEmpiricalDiscoveryTest`.

```
./gradlew :knes-agent:test --tests "*Live*"   # opt-in, needs ANTHROPIC_API_KEY
```

---

## Useful CLI

```bash
# Build memory cheaply (Haiku, no Opus). Default 20 runs / $1 cap.
./gradlew :knes-agent:runExplorer

# Override savestate (interior fixture starts already inside Coneria Town):
FF1_SAVESTATE=knes-agent/src/test/resources/fixtures/ff1-coneria-interior-discovery.savestate \
  ./gradlew :knes-agent:runExplorer

# Existing AgentSession (Phase 2 reads landmarks.json):
./gradlew :knes-agent:runAgent

# Targeted tests
./gradlew :knes-agent:test --tests "*SalienceStrategy*"     # priorities incl. 0
./gradlew :knes-agent:test --tests "*HandleNewInterior*"    # decideClassification
./gradlew :knes-agent:test --tests "*DetectTrigger*"        # trigger gating

# Inspect live memory
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq '[.landmarks | group_by(.kind)[] | {k: .[0].kind, n: length, mapIds: ([.[].mapId, .[].mapIdInterior] | map(select(. != null)) | unique)}]' ~/.knes/ff1-landmarks.json
jq '.tiles' ~/.knes/ff1-overworld-warps.json
jq '[.blockages[-10:]]' ~/.knes/ff1-blockages.json
```

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Worktree: `/Users/askowronski/Priv/kNES-ff1-agent-v2`
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent memory: `~/.knes/ff1-{overworld-terrain,landmarks,blockages,overworld-warps,interior-memory}.json`
- Archives:
  - `~/.knes/archive-2026-05-05-pre-mapid-fix/` (state before #106)
  - `~/.knes/archive-2026-05-05-pre-warp-targeting/` (state before #107/#108)
  - `~/.knes/archive-2026-05-05-pre-warmup/` (state before #109; includes `ff1-overworld-warps.json.with-bogus` — the 4-warp file before manual cleanup)

## Test fixtures
- `ff1-post-boot.savestate` — overworld at (146, 158)
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8 at party=(11, 32)

---

## First message to send to next session (suggestion)

> Master at `ab0b677`. Four PRs merged this session (#106, #107, #108, #109); #105 closed unmerged. Phase 1.5 mapId mismatch fully verified live: tonight's overworld campaign produces 7× NPC_KING tagged mapId=24 (Coneria Castle), entry landmark at (146,152) tagged mapIdInterior=8 (overlay) — exactly the multi-stage warp behaviour the morning campaign's bug had obscured. Top priorities: (A) filter mapId=0 records out of `OverworldWarpMemory` auto-detection in `AgentSession.kt:194` (currently records UnknownMapTrap as a warp); (B) Phase 2 LandmarkContext live validation via `runAgent` with the now-populated landmarks; (C) UnknownMapTrap recovery (every overworld run currently ends in mapId=0 trap from Castle's stairs). Conversation in Polish; PR-flow + tests-first + commit per closed phase.
