# FF1 Koog Agent — Handoff (Phase 1.5 + Phase 2 closed, 2026-05-05)

**Master HEAD:** `f13bd98` — PR #102 merged
**Required env:** `ANTHROPIC_API_KEY` (live runs only; tests run without it)

---

## TL;DR — where we are

Today's session shipped **PR #102** (4 commits on top of PR #101 from earlier same day):

```
61328df  fix     SalienceStrategy + LandmarkMemory — confirmed-vs-tagged tier
a6c5e97  test    live HTTP smoke test for AnthropicHaikuConsult
56c9ddd  Phase 2 AgentSession reads explorer landmarks
0098eb2  Phase 1.5 wire real Haiku 4.5 in AnthropicHaikuConsult
```

End-to-end pipeline ALMOST works: explorer can run cheap multi-run campaigns,
salience prefers confirmed entries over stale tile-tags, AgentSession reads
landmarks.json on startup. **Goal still not validated** — Haiku trigger
handlers never fire because every map the agent enters is already in
`~/.knes/ff1-interior-memory.json` (3 maps pre-seeded from prior sessions).
`detectTrigger` only returns `NewInteriorEntered` for maps where
`!interiorMemory.hasMapBeenSeen(mapId)`. So 0 NPC classifications, $0 spent.

**Live `runExplorer` after fix: 16 runs, Plateau, 79 landmarks discovered,
0 confirmed entries, $0.** The salience+memory bug is fixed; the trigger
gating is the next bottleneck.

---

## What landed this session

### Phase 1.5 (commit 0098eb2)

`AnthropicHaikuConsult` stub → real Haiku 4.5 (`claude-haiku-4-5`).
- Direct HTTP to `api.anthropic.com/v1/messages` (mirrors VisionOverworldNavigator).
- Two prompts: `classifyInterior` (post-explore screenshot → NPC list as JSON)
  and `readDialog` (dialog screenshot → summary + landmarkHint).
- Cost from `usage.input_tokens` / `usage.output_tokens` × Haiku 4.5 pricing
  ($1/MT in, $5/MT out).
- Interface param renamed `screenshotPng:ByteArray?` → `screenshotBase64:String?`
  (toolset.getScreen() already returns base64 — no round-trip).
- 7 pure-unit parse tests + 2 live HTTP smoke tests (skip without API key).

### Phase 2 (commit 56c9ddd)

`AgentSession` consumes `~/.knes/ff1-landmarks.json`.
- New `runtime/LandmarkContext.kt` — pure formatter; groups by kind (TOWN →
  CASTLE → DUNGEON → NPC_KING → NPC_SHOPKEEPER → ...), shows world coords +
  `mapIdInterior` destination for entries, `[visited]` flag, note truncated to
  60 chars. Returns null on empty memory.
- `AgentSession` constructor: `landmarkMemory: LandmarkMemory = LandmarkMemory()`.
  Default loads from disk; tests pass tmp file.
- Injection in 2 places: advisor obs (alongside warp-tile section) and
  executor input prompt.
- Main.kt wires `LandmarkMemory()` into AgentSession.
- 5 LandmarkContext tests.

### Salience + LandmarkMemory fix (commit 61328df)

Two correlated bugs blocking Phase 1.5 end-to-end validation:

1. `LandmarkMemory.recordIfNew` discarded duplicates instead of upgrading. So
   when `SalienceStrategy` priority 2 auto-recorded a tile-tagged candidate
   `(visited=false, mapIdInterior=null)`, then later `handleNewInterior` tried
   to record `(visited=true, mapIdInterior=8)` at the same coords, the strong
   confirmation was thrown away. Stale tile-tags stayed `visited=false` forever.
   - Fix: monotonic field upgrade on dup (visited=OR, mapIdInterior fills if
     null, note + runId fill if blank). Return value still `false` (no new
     record added) — call sites unaffected.

2. `SalienceStrategy.pickOverworldTarget` priority 1 picked any unvisited
   landmark by manhattan distance regardless of source. With 120 stale
   tile-tagged records from prior runs scattered across the map (X 100-200,
   Y 155-236), priority 1 always returned a far unreachable point. Agent
   idled → plateau in 3 runs at $0.
   - Fix: priority 1 split into 1A (`mapIdInterior != null` confirmed entries,
     no distance limit) and 1B (`mapIdInterior == null` tile-tagged, manhattan
     ≤ `tileTaggedDistanceLimit=40` = 2 × frontierRadius).

5 new tests (3 SalienceStrategy + 2 LandmarkMemory). One existing test updated
to set `mapIdInterior=8` for its "far landmark wins" semantic.

---

## Read first (next session, in this order)

1. **This file**, then `git log --oneline -8 origin/master` to see latest commits.
2. `knes-agent/src/main/kotlin/knes/agent/explorer/SingleRun.kt:99-105` —
   `detectTrigger`. Note the `!interiorMemory.hasMapBeenSeen(phase.mapId)` gate.
3. `~/.knes/ff1-interior-memory.json` — pre-seeded map IDs that block triggers.
4. `knes-agent/src/main/kotlin/knes/agent/explorer/SalienceStrategy.kt` —
   priority 1A vs 1B logic; key tunable: `tileTaggedDistanceLimit` (default 40).
5. `knes-agent/src/main/kotlin/knes/agent/runtime/LandmarkContext.kt` —
   what the agent now sees in its prompt.
6. `~/.knes/ff1-landmarks.json` — live state (currently 120 tile-tagged
   records from before fix; never confirmed). Backup at
   `~/.knes/ff1-landmarks.json.before-fix-2026-05-05`.

---

## Top priority for next session

**Trigger gating fix.** `detectTrigger` returns `null` for any interior the
agent has ever entered before (across all sessions, persistently). To validate
Phase 1.5 end-to-end, NPC classification must fire on at least one re-entry.

Options:

- **(A) Per-run novelty.** Track `novelMapIdsThisRun: Set<Int>` in `SingleRun`,
  fire trigger first time agent enters each map THIS run regardless of prior
  sessions. Bounded re-call (~3-5 maps/run).
- **(B) Re-classification policy.** Trigger if existing landmarks for that
  mapId have zero NPC entries (suggesting prior classification was no-op stub).
  Cleaner but couples explorer to landmark-memory state.
- **(C) Force-fresh fixture.** Clear `~/.knes/ff1-interior-memory.json` at
  campaign start. Validates the pipeline once; doesn't scale.

(A) is probably right. Sketch:

```kotlin
// in SingleRun
private val novelMapIdsThisRun: MutableSet<Int> = mutableSetOf()

private fun detectTrigger(phase: FfPhase, ram: Map<String, Int>): Trigger? = when {
    phase is FfPhase.Indoors && phase.mapId !in novelMapIdsThisRun ->
        Trigger.NewInteriorEntered(phase.mapId).also { novelMapIdsThisRun += phase.mapId }
    phase is FfPhase.Battle -> Trigger.BattleEntered
    isDialogBoxOpen(ram) -> Trigger.DialogBoxVisible
    else -> null
}
```

Then run live to validate Phase 1.5: at least one `interior_entry_<mapId>_<X>_<Y>`
record in `~/.knes/ff1-landmarks.json` with `mapIdInterior != null` and at
least one Haiku-classified `NPC_*` landmark, total cost > $0.

## Open work / tech debt

1. **Memory files non-atomic save.** All persistent memories use direct
   `writeText()` — crash mid-save corrupts. Need write-temp-then-rename.
2. **Explorer doesn't accumulate warps cross-run.** Warps detected by
   AgentSession persist; explorer's discoveries don't (it doesn't write to
   `OverworldWarpMemory`).
3. **`isDialogBoxOpen` returns false.** `Trigger.DialogBoxVisible` never fires.
   Need a real RAM signature or screenshot-diff heuristic.
4. **Anthropic prompt caching still no-op.** Koog 0.6.x lacks `cache_control`
   field on `SystemAnthropicMessage`. Custom HttpClient interceptor or upgrade
   to Koog ≥0.7.
5. **AgentSession + landmarks not yet validated live.** `LandmarkContext`
   injection is unit-tested but the agent's actual behavior with populated
   landmarks (does it route to known towns? does Garland stuck-pattern improve?)
   is unverified.

---

## Test status

```
./gradlew :knes-agent:test
```

**191 pass / 2 pre-existing failures / 7 skipped** (master HEAD f13bd98).

Pre-existing failures: `Coneria8VisualDiffTest`, `ConeriaTownEmpiricalDiscoveryTest`.

```
./gradlew :knes-agent:test --tests "*Live*"   # opt-in, needs ANTHROPIC_API_KEY
```

---

## Live evidence: campaign 2026-05-05T16:45 (post-fix)

State setup: cleared `~/.knes/ff1-landmarks.json` to empty (backup at
`.before-fix-2026-05-05`), kept other memory files.

```
[campaign] FINAL: CampaignResult(
  outcome=Plateau, runsExecuted=16,
  coverage=CoverageStats(terrainTilesKnown=65502, warpsKnown=4,
    landmarksKnown=79, landmarksVisited=0, interiorMapsExplored=3),
  totalCostUsd=0.0)
```

Per-run stop reason distribution (16 runs): Idle ×7, LocalPlateau ×4,
UnknownMapTrap ×5 — meaningful improvement over the 3-run pre-fix plateau.

State restored after test: `cp ff1-landmarks.json.before-fix-2026-05-05
ff1-landmarks.json` (120 records back).

---

## Useful CLI

```bash
# Build memory cheaply (Haiku, no Anthropic-Opus). Default 20 runs / $1 cap.
./gradlew :knes-agent:runExplorer

# Existing agent. Reads landmarks.json on startup (Phase 2).
./gradlew :knes-agent:runAgent

# Tests
./gradlew :knes-agent:test                                   # full suite
./gradlew :knes-agent:test --tests "*Live*"                  # opt-in live
./gradlew :knes-agent:test --tests "*AnthropicHaikuConsult*" # parsing only

# Inspect live memory
jq '.landmarks | length' ~/.knes/ff1-landmarks.json
jq '.landmarks | group_by(.kind) | map({k: .[0].kind, n: length, confirmed: (map(select(.mapIdInterior != null)) | length)})' ~/.knes/ff1-landmarks.json
jq '.' ~/.knes/ff1-overworld-warps.json
```

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Worktree (ff1-agent-v2-4, now identical to master): `/Users/askowronski/Priv/kNES-ff1-agent-v2`
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent memory: `~/.knes/ff1-{overworld-terrain,landmarks,blockages,overworld-warps,interior-memory}.json`
- Run traces: `~/.knes/runs/<ISO-timestamp>/trace.jsonl` (override via `KNES_RUN_DIR`)

## Test fixtures

- `ff1-post-boot.savestate` — overworld at (146, 158)
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8

---

## First message to send to next session (suggestion)

> Worktree `kNES-ff1-agent-v2` on branch `ff1-agent-v2-4` is now identical to
> master at `f13bd98` (PR #102 merged: Phase 1.5 real Haiku, Phase 2 AgentSession
> reads landmarks, salience+landmark-memory fix). End-to-end pipeline blocked
> on `detectTrigger` gating: `interiorMemory.hasMapBeenSeen` returns true for
> all 3 pre-seeded maps so `NewInteriorEntered` never fires and Haiku is never
> called ($0 every campaign). Top priority: add per-run `novelMapIdsThisRun`
> to `SingleRun.detectTrigger`. Then validate Phase 1.5 + Phase 2 with a live
> `runExplorer` followed by a live `runAgent` with populated `landmarks.json`.
> Conversation in Polish; user prefers PR flow + tests-first + commit per closed phase.
