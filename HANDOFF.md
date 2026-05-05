# FF1 Koog Agent — Handoff (mapId fix shipped, V5.2 quirk blocks live verification)

**Master HEAD:** `7be39be` — PR #108 merged (PR #106 + #107 + #108 this session; #105 closed unmerged)
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — three PRs harden the explorer; one bug verified live, one blocked

Phase 1.5 mapId-mismatch investigation closed for code; live verification partially complete.

```
7be39be  PR #108 merge: priority 0 must not filter recentlyFailed (V5.2 quirk)
30fb464  PR #107 merge: salience priority 0 — target known warp tiles deliberately
fc25534  PR #106 merge: tag landmarks with live mapId; skip Haiku on wipe / overworld
26f8b1d  docs(handoff): close prior session — Phase 1.5 validated end-to-end
```

PR #105 (println diagnostic) closed without merge — served its purpose, the
captured data drove #106; printlns aren't worth keeping in master.

---

## What was wrong (root cause from #105 diagnostic)

Phase 1.5's morning campaign reported `interior_entry_8_145_152` (mapIdInterior=8, kind=TOWN_ENTRY) and Haiku then described "throne room with crowned figure" — inconsistent with mapId=8 being Coneria Town. Two co-occurring bugs in `handleNewInterior`:

### A. Stale `t.mapId` tag
`Trigger.NewInteriorEntered.mapId` is captured the instant `phase` becomes `Indoors`. FF1 multi-stage warps briefly expose a transient `currentMapId`: `(145,152)` Coneria warp → `mapId=8` outer overlay → `mapId=24` inner (per the V5.25 comment in `OverworldWarpMemory.kt:13`). By the first `ramSnapshot()` inside `handleNewInterior` the value has stabilised, but the entry landmark + Haiku call were both still using `t.mapId`.

**Fix:** entry landmark + classification now use `ram["currentMapId"]` (falling back to `t.mapId` only if RAM unreadable). `handleNewInterior` returns the effective mapId so the caller adds **both** trigger and stabilised mapIds to `novelMapIdsThisRun` (avoids re-firing the trigger after the engine completes the warp).

### B. PartyWipe / overworld-return during 80-step explore corrupts Haiku
A wipe inside `exploreInteriorFrontier` returns the player to the title screen; the post-explore `getScreen()` captures that frame and Haiku misclassifies UI text. **Live evidence (run-1, ff1-coneria-interior-discovery fixture):** `NPC_GENERIC` recorded with note _"Small figure sprite to the left of 'NEW GAME' text"_, tagged mapId=8.

**Fix:** new `decideClassification` companion-helper gates the Haiku call on `mapflags=1 && hpSum > 0`. Returns null → skip classification, no Haiku cost, no garbage landmarks.

---

## Live verification status

| Bug | Verified live? | Evidence |
|---|---|---|
| **B (PartyWipe gate)** | ✅ | 20-run interior-fixture campaign post-#106: run-1 stop=PartyWiped cost=**$0.0** (was $0.000578); only entry landmark recorded, zero "NEW GAME" garbage |
| **A (live mapId tag)** | ⚠️ unit-tested only | Fixture savestate (146,152) enters mapId=8 directly with no transition; multi-stage warp from (145,152) needs an overworld run that reaches the warp tile — blocked by V5.2 quirk below |

20-run interior-fixture campaign produced 30 landmarks: 13 NPC_GENERIC + 4 STAIRS_DOWN + 7 TOWN_ENTRY + 6 stale CASTLE_ENTRY (overworld leftover). Cost $0.0128. **Zero NPC_KING hallucinations** — confirms mapId=8 is genuinely Coneria Town (no throne); morning's "throne room" classification was the multi-stage-warp staleness now fixed.

---

## V5.2 loadState quirk — blocks overworld live verification

After PR #107 (priority 0 = known warp tiles) and PR #108 (priority 0 doesn't filter `recentlyFailed`), live overworld campaigns still plateau at **3 runs / 0 entries / $0**.

Trace from blockage memory:
```
runId=run-1-22:19, from=(146,158), attemptedTo=(147,154):
"input not responding: 3 consecutive non-moving steps from (146,158)
 toward (147,154). Likely cause: fixture loadState quirk (V5.2) or
 party physically boxed in by terrain. Fog has been marked accordingly."
```

The agent doesn't move once in 10 idle iterations after `loadState`. Priority 0 retries the same warp every iteration (intended), but the emulator drops input frames longer than the idle threshold (10 turns) tolerates. Run ends Idle, never enters.

This is **the** thing that needs to be fixed to unblock everything else.

---

## Top priorities for next session

### A. Fix the V5.2 loadState quirk (highest leverage)
Add a warm-up before the first `deterministicStep` of each run in `ExplorerSession.run`: `~30-60 frames of toolset.sequence([])` (or equivalent NOOP) so the emulator stabilises before the agent reads RAM and tries to walk. Without this, every overworld campaign plateaus at 0 entries regardless of how smart salience is.

Alternative: detect "input not responding" inside `SingleRun.execute` and skip incrementing `idleTurns` while the emulator is warming up. Less surgical, more robust.

Once fixed: re-run `runExplorer` on `ff1-post-boot` overworld savestate, expect run-1 to enter `(147,154)` or `(145,152)` and the entry landmark to be tagged with the **live RAM mapId** (closes Fix A live verification).

### B. Phase 2 LandmarkContext live validation (was Priority B in prior HANDOFF)
`AgentSession` reads `landmarks.json` at startup; the `LandmarkContext` injection's effect on agent behaviour is unverified. Run `runAgent` with the populated landmarks file and observe advisor/executor traces.

### C. UnknownMapTrap recovery (was Priority D)
Run-3 of an earlier validation campaign hit `stop=UnknownMapTrap` (mapId=0, mapflags=1). V5.31 panic-reset guard restricts `pressStartUntilOverworld` to TitleOrMenu phase, so a different escape strategy is needed.

### D. Auto-detected warps may be false positives
`~/.knes/ff1-overworld-warps.json` has 3 auto-detected entries: `(144,153)`, `(147,153)`, `(147,154)`. Only `(145,152)` is manually annotated as the real Coneria warp. Auto-detection logic in `OverworldWarpMemory` should be reviewed — false-positive warps would make priority 0 pick unreachable / non-warp tiles.

---

## Code architecture (post-#108)

```
knes-agent/src/main/kotlin/knes/agent/
  explorer/
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
    ExplorerSession.kt: salience now constructed with warpMemory
```

---

## Read-first files (next session, in this order)

1. **This file** + `git log --oneline -8 origin/master`.
2. `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt` — handleNewInterior + decideClassification.
3. `knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt` — priority 0 + comment about V5.2.
4. The "input not responding" message — likely in `WalkOverworldTo` or `ExploreOverworldFrontier` skill. Find it, understand the threshold, decide warm-up vs idle-skip.

---

## Test status

```
./gradlew :knes-agent:test
```

**213 pass / 2 pre-existing fail / 7 skipped** (master HEAD `7be39be`).

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
jq '[.landmarks | group_by(.kind)[] | {k: .[0].kind, n: length, confirmed: (map(select(.mapIdInterior != null)) | length)}]' ~/.knes/ff1-landmarks.json
jq '.tiles | map([.worldX, .worldY])' ~/.knes/ff1-overworld-warps.json
jq '[.blockages[] | select(.result | test("input not responding"))] | length' ~/.knes/ff1-blockages.json
```

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Worktree: `/Users/askowronski/Priv/kNES-ff1-agent-v2`
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent memory: `~/.knes/ff1-{overworld-terrain,landmarks,blockages,overworld-warps,interior-memory}.json`
- Archives:
  - `~/.knes/archive-2026-05-05-pre-mapid-fix/` (state before #106)
  - `~/.knes/archive-2026-05-05-pre-warp-targeting/` (state before #107/#108 live test)

## Test fixtures
- `ff1-post-boot.savestate` — overworld at (146, 158)
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8 at party=(11,32)

---

## First message to send to next session (suggestion)

> Master at `7be39be`. PRs #106 + #107 + #108 merged this session; #105 closed unmerged. Bug B (PartyWipe gate) verified live ($0 cost, no NEW GAME garbage). Fix A (live mapId tag) unit-tested but not live-verified — blocked by **V5.2 loadState quirk**: emulator drops input frames after `loadState`, agent doesn't move within the 10-turn idle threshold, every overworld campaign plateaus at 0 entries. Top priority: warm-up after loadState in `ExplorerSession.run` (or equivalent — see HANDOFF priority A). Once unblocked, re-run overworld campaign and confirm `(145,152)` or `(147,154)` entry tags `mapIdInterior=24` not 8. Conversation in Polish; PR-flow + tests-first + commit per closed phase.
