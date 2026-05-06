# FF1 Koog Agent — Handoff (post-mapId-mismatch hardening, 2026-05-06 morning)

**Master HEAD:** `ebe52a3` — PR #114 merged. **Nine PRs this session: #106 + #107 + #108 + #109 + #110 + #111 + #112 + #113 + #114** (#105 closed unmerged).
**Required env:** `ANTHROPIC_API_KEY` (default vision backend) or `GEMINI_API_KEY` (when `KNES_VISION=gemini-pro`).

---

## TL;DR — Phase 1.5 + 2 closed live, explorer hardened end-to-end

Started session with one bug ("mapId mismatch in morning campaign") and ended with the entire explorer + agent Phase 2 pipeline verified live, plus a vision backend swap available.

```
ebe52a3  PR #114 merge: atomic JSON memory file saves — crash-safe writes
c4baf89  PR #113 merge: bail immediately on mapId=0 + mapflags=1 (UnknownMapTrap)
f025f1a  PR #112 merge: GeminiVisionConsult — alt vision backend (Gemini 2.5 Pro)
5cd7c11  PR #111 merge: skip handleNewInterior when ram mapId=0
bf00bed  PR #110 merge: filter mapId=0 in auto-detected warps (AgentSession)
ab0b677  PR #109 merge: post-loadState warm-up — bridges V5.2 input-frame drop
30fb464  PR #107 merge: salience priority 0 — target known warp tiles
7be39be  PR #108 merge: priority 0 must not filter recentlyFailed
fc25534  PR #106 merge: tag landmarks with live mapId; skip Haiku on wipe / overworld
```

---

## Verified live this session

| Bug / feature | Live evidence |
|---|---|
| Fix A — live mapId tag (#106) | Overworld campaign produced 7× NPC_KING tagged `mapId=24` (Castle), entry tagged `mapIdInterior=8` (Coneria Town overlay). Pre-fix the throne was tagged `mapId=8`. |
| Fix B — PartyWipe gate (#106) | Interior-fixture campaign run-1 stop=PartyWiped → cost=$0.0 (was $0.000578 pre-fix), zero "NEW GAME" garbage. |
| Priority 0 warp targeting (#107/#108) | First overworld run reached (147,154) for the first time across the entire session. |
| LoadState warm-up (#109) | Without 30 NOOP frames after loadState, every first walk fails "input not responding". With it, agent moves on iter-1. |
| Auto-detect mapId=0 filter (#110) | Real Haiku 4.5 + Gemini 2.5 Pro empirical comparison on the void-state screen confirmed 4-vs-0 false positive ratio. |
| Skip handleNewInterior on mapId=0 (#111) | Same evidence as #110. |
| Gemini 2.5 Pro alt backend (#112) | A/B on real Castle throne (mapId=24): both correctly identify NPC_KING; Haiku also hallucinates a STAIRS_DOWN, Pro doesn't. Pro adds accurate detail _"flanked by two golden dragon emblems"_. |
| Trap bail-early (#113) | Unit-tested; with #111 already in place the live impact is freeing ~80 frames per trap-hitting run. |
| Atomic memory saves (#114) | Unit-tested via simulated mid-write crash. Operational impact: today's debugging campaigns lost state to non-atomic saves several times — won't repeat. |
| Phase 2 LandmarkContext (#106 byproduct) | `runAgent` trace in `~/.knes/runs/2026-05-06T05-36-14.521274Z/trace.jsonl`: rendered landmark block in advisor input, advisor output references (146,152) + "castle with King/throne room" verbatim. |

---

## Vision backend comparison (live A/B, 2 data points)

| Screen | Haiku 4.5 | Gemini 2.5 Pro | Truth |
|--------|-----------|-----------------|-------|
| **UnknownMapTrap mapId=0 void** | 4 false: NPC_KING + NPC_GENERIC + 2× STAIRS_DOWN | `[]` (empty) | empty (no real interior) |
| **Coneria Castle throne mapId=24** | NPC_KING ✓ + STAIRS_DOWN ✗ (false) | NPC_KING ✓ with accurate detail | NPC_KING only |

**Pattern:** Haiku over-generates landmarks (especially false-positive STAIRS), Pro is conservatively accurate. Cost ratio ~6x ($0.005-0.007 vs $0.001 per call). For a 50-run campaign: $0.30 (Pro) vs $0.05 (Haiku) — absolute differential trivial; precision gain matters more.

Switch via env var: `KNES_VISION=gemini-pro` (default `haiku`).

---

## Open work

### High-value
- **F. Full A/B campaign** — 20 runs each backend, empirical campaign-level metrics. Marginal value vs the 2 data points already collected, but cleanest dataset for "should we default to Pro?".
- **E. Coverage metric** — `terrainTilesKnown delta=0` every run because savestate has full overworld scan. Replace with `visited-landmarks` delta in plateau heuristic.

### Smaller / opportunistic
- **Discover more warps** — current `~/.knes/ff1-overworld-warps.json` has only the manually-annotated `(145,152)`. AgentSession's auto-detect (now mapId-correct via #110) needs runs to populate. Without trap recovery — wait, that's already done via #111+#113. So just need actual runs producing data.
- **Explorer doesn't accumulate warps cross-run** (only AgentSession does). Harmonise so explorer can grow `warpMemory` from its own discoveries.
- **`isDialogBoxOpen` is a `false` stub** — `Trigger.DialogBoxVisible` never fires. Need a real RAM signature.
- **Anthropic prompt caching is no-op** — Koog 0.6.x lacks `cache_control`. The HaikuConsult HTTP path is direct; could add it.

---

## Code architecture (post-#114)

```
knes-agent/src/main/kotlin/knes/agent/
  explorer/
    ExplorerSession.kt — V5.2 warmup post-loadState (#109)
    ExplorerMain.kt — KNES_VISION env router (haiku | gemini-pro)
    SingleRun.kt
      handleNewInterior(): live mapId tag, skip on mapId=0 (#106 + #111)
      Companion: decideClassification(triggerMapId, ramAfter): null on
                 wipe / overworld / mapId=0 (#106 + #111)
      checkRestart: mapId=0+mapflags=1 → immediate UnknownMapTrap (#113)
    SalienceStrategy.kt
      Priority 0: closest known warp not yet entered this run (#107),
                  no recentlyFailed filter (#108)
      Priorities 1A/1B/2-5 unchanged
    AnthropicHaikuConsult.kt — Haiku 4.5 backend
    GeminiVisionConsult.kt — Gemini 2.5 Pro backend (#112)
  perception/
    AtomicJsonWriter.kt — write-temp-then-rename (#114)
    {Landmark,Blockage,OverworldWarp,Interior,OverworldTerrain,Overworld}Memory.kt
      — all save() now via AtomicJsonWriter
  runtime/
    AgentSession.kt
      Companion: FAILED_WARP_REGEX captures (worldX, worldY, mapId)
      Auto-detect skips mapId=0 transitions (#110)
      LandmarkContext.render(landmarkMemory) injected into both advisor +
      executor observations on every phase change (Phase 2)
    LandmarkContext.kt — unchanged; renders LandmarkMemory grouped by kind.
```

---

## Test status

```
./gradlew :knes-agent:test
```

**233 pass / 2 pre-existing fail / 7 skipped** (master HEAD `ebe52a3`).

Pre-existing failures: `Coneria8VisualDiffTest`, `ConeriaTownEmpiricalDiscoveryTest` (unchanged from master baseline at session start).

---

## Useful CLI

```bash
# Cheap explorer with default Haiku backend.
./gradlew :knes-agent:runExplorer

# Same campaign with Gemini 2.5 Pro vision backend.
KNES_VISION=gemini-pro ./gradlew :knes-agent:runExplorer

# Override savestate to interior fixture.
FF1_SAVESTATE=knes-agent/src/test/resources/fixtures/ff1-coneria-interior-discovery.savestate \
  ./gradlew :knes-agent:runExplorer

# Phase 2 agent (uses LandmarkContext).
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
  - `~/.knes/archive-2026-05-05-pre-mapid-fix/`
  - `~/.knes/archive-2026-05-05-pre-warp-targeting/`
  - `~/.knes/archive-2026-05-05-pre-warmup/`

## Test fixtures
- `ff1-post-boot.savestate` — overworld at (146, 158)
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8 at party=(11, 32) (warning: PPU vblank desync per V5.16; Coneria8VisualDiffTest uses live boot instead)

---

## First message to send to next session (suggestion)

> Master at `ebe52a3`. Nine PRs merged this session (#106 + #107 + #108 + #109 + #110 + #111 + #112 + #113 + #114; #105 closed unmerged). Phase 1.5 + Phase 2 verified live. Vision backend swappable (Haiku default; `KNES_VISION=gemini-pro` for Gemini 2.5 Pro — better on edge cases per 2-point A/B). Memory writes are now crash-safe. UnknownMapTrap recovery in place. 233 / 2 pre-existing fail / 7 skipped. Open: full A/B campaign (20 runs each backend) for empirical metrics, coverage metric improvement (terrainTilesKnown delta is always 0 — needs replacement with visited-landmarks delta). Conversation in Polish; PR-flow + tests-first + commit per closed phase.
