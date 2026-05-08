# FF1 Koog Agent — Handoff (Spec 3a bridge→ToF + autonomous-run cold-start, 2026-05-08)

**Branch HEAD:** `af500d0` on `ff1-bridge-and-walk-to-tof`. **No PR open.**
**Parent branches:** `ff1-buy-at-shop` (Spec 2 + savestate cleanup) → `ff1-grind-strategy` (Spec 1, PR #116).
**Tests:** 302 unit, 0 new regressions, 3 pre-existing baseline failures unchanged.

## What this session shipped

### Spec 3a — bridge → walk to Temple of Fiends entrance
Spec doc: `docs/superpowers/specs/2026-05-07-ff1-bridge-and-walk-to-tof-design.md`
Plan: `docs/superpowers/plans/2026-05-07-ff1-bridge-and-walk-to-tof.md`

7 implementation commits (`7f8c489` → `091e2ac`):
- `LandmarkKind.TEMPLE_ENTRY` + `LandmarkMemory.findTempleEntry()` helper.
- `HaikuConsult.classifyOverworldLandmark(image, kind)` interface method. `OverworldClassification` sealed type. Gemini implementation with chaos-shrine-specific prompt + parser. Anthropic stub returns `NotFound`.
- `DiscoverChaosShrine` skill — captures viewport, calls Gemini, derives world coords via `ViewportMap.localToWorld`, persists `TEMPLE_ENTRY` landmark.
- `BridgeTick` state machine — per-iteration: discover → walk → adjacency. Caps: 3 discover failures, 5 consecutive walk no-progress = sticky `BailToLlm`. `"encounter triggered"` message = no-stall continue.
- `AgentSession` integration — 2 new optional ctor params, `bridgePhaseActive` flag (mutually exclusive with `grindModeActive`), main-loop gate, BRIDGE-decision flip site updated, terminal `bridge_phase_summary` trace events.

15 new unit tests covering all 9 rows of spec §5 error matrix.

### Empirical fixes from real-API + real-ROM testing (5 commits)

`9f39387` **fix(vision): bump overworld classify maxOutputTokens 400→2000.** `gemini-2.5-pro` mandates thinking mode (`thinkingBudget=0` returns `400 Bad Request`); thinking consumes 400-600 tokens before producing output. Initial 400-token cap → empty responses 100% of the time.

`29b3eee` **fix(vision): correct overworld viewport dimensions in prompt.** NES screen is 256×240 px = 16 tiles wide × **15** tall (not 16×16). Party renders at tile (8, 7), not (8, 8). `screenY` range 0..14 (not 0..15). Empirically verified by inspecting actual screenshots from `/screen` endpoint.

`4defa54` **feat(main): wire HaikuConsult + outfit/bridge deps for autonomous agent runs.** `Main.kt` previously didn't wire any vision deps → boot phase + bridge phase silently no-op'd. Selected via `KNES_VISION` env var (mirrors `ExplorerMain` pattern); `gemini-pro` + `GEMINI_API_KEY` → `GeminiVisionConsult`, otherwise Anthropic Haiku stub.

`d74caff` **fix(session): defer OutfitBoot until first Overworld observation.** Previously `runOutfitBootPhase()` ran at the very top of `run()`, before any emulator stepping. At that point party is still at title screen, `worldX=worldY=0`, RAM uninitialized. `WalkOverworldTo(coneriaEntry)` failed with "stuck at (0,0): no path within viewport". Fix: gate boot trigger on first `phase is Overworld` observation inside the main loop.

`af500d0` **fix(boot): settle warp transition after walk-to-coneria.** `WalkOverworldTo` terminates the moment party RAM coords match destination, but FF1 engine's warp tile triggers a screen-fade + map-load that takes ~30 frames to update `currentMapId`. Without settling, step 3's `WalkInteriorVision` saw `mapId=0` → `NotInBuilding` → boot bailed before probing for shop. Fix: 30-frame idle + cardinal-tap nudge after walk.

### Spec 2 cleanup (carried over from earlier in session)

Commit `9de2e94` on parent branch `ff1-buy-at-shop`: removed savestate-keyed `OutfitState` (file persistence, hash gate) in favor of RAM-derived skip (`StrategyContext.anyWeaponEquipped` for all 4 chars). Deleted `OutfitState.kt`, `OutfitStateTest.kt`, `OutfitBootPhaseE2ETest.kt`. -420 LOC. New no-savestate policy: `feedback_no_savestate.md`.

## Empirical validation results

### What works (validated against real services)
- Gemini 2.5 Pro API + production `SYSTEM_OVERWORLD_LANDMARK` prompt: returns `{"found": false}` reliably on overworld viewports without chaos shrine. Cost ~$0.005-0.007 per call.
- `JSON_OBJECT` regex parser handles markdown-fenced JSON Gemini sometimes wraps responses in.
- knes-api headless server + knes-mcp ROM driving + 256×240 PNG capture pipeline.
- Spec 3a `BridgeTick` state machine — all 8 unit tests covering bail caps, encounter-aware stall, sticky bail.
- `AgentSession.runOutfitBootPhase` correctly fires after first Overworld observation (post-`d74caff`).
- Warp transition completes within 30-frame settle window (post-`af500d0`).

### What does NOT yet work (next steps for future session)

**Cold-start shop discovery is brittle.** Run #4 reached `boot_walk_settle: postWalkMapId=8` (party in Coneria town) but failed at:
```
boot_shop_probe: attempt=0 classify=ClassifyFailed: vision returned unknown
boot_outfit_summary: boot_shop_not_found
```

Root cause: `discoverWeaponShop`'s `maxAttempts = max(1, NPC_SHOPKEEPER landmark count)`. With **zero** seeded landmarks (cold start), only 1 probe attempt. The `WalkInteriorVision` (LLM-driven) walks somewhere, opens A-button menu, sends to Gemini for classification. If the menu isn't a BUY menu (or Gemini returns "unknown"), the boot phase exits.

Why Gemini returned "unknown" on attempt #0: probably because the probe didn't actually open a shop BUY menu — it might have walked into a non-shop tile and triggered a generic dialog instead. `WalkInteriorVision` is non-deterministic.

**Bridge phase never fired** — Spec 3a code path requires `min(char_level) ≥ 3` from grind which requires equipped weapons from outfit boot which is currently blocked above. So the actual `DiscoverChaosShrine` + `BridgeTick` code path is **unverified end-to-end against real ROM**.

**Adaptive anchor shift works** — run #4 logged `[strategy:grind] adaptive shift #1: anchor (146,158) -> (148,154)` after `NoEncounter`. Spec 1's adaptive grind eventually shifts toward the bridge area, but burns wall-clock budget along the way.

## Branch state

```
ff1-bridge-and-walk-to-tof  HEAD af500d0  (12 commits ahead of ff1-buy-at-shop)
  ↑ cut from
ff1-buy-at-shop             HEAD past 9de2e94  (Spec 2 + savestate cleanup)
  ↑ cut from
ff1-grind-strategy          PR #116 open (Spec 1)
  ↑ open against
master
```

12 commits on Spec 3a branch:
```
af500d0 fix(boot): settle warp transition after walk-to-coneria
d74caff fix(session): defer OutfitBoot until first Overworld observation
4defa54 feat(main): wire HaikuConsult + outfit/bridge deps for autonomous agent runs
29b3eee fix(vision): correct overworld viewport dimensions in prompt
9f39387 fix(vision): bump overworld classify maxOutputTokens 400→2000
091e2ac feat(session): wire BridgeTick — flip on BRIDGE, gate Overworld ticks, log summary
5090824 feat(runtime): BridgeTick — deterministic post-BRIDGE walk to ToF entrance
e42e5a8 feat(skill): DiscoverChaosShrine — vision-classify ToF entrance, persist landmark
e6ed68e feat(vision): Gemini classifyOverworldLandmark — chaos shrine prompt + parser
88aa89f feat(vision): add HaikuConsult.classifyOverworldLandmark + stubs
8492d3e feat(perception): add LandmarkMemory.findTempleEntry() helper
7f8c489 feat(perception): add LandmarkKind.TEMPLE_ENTRY for ToF entrance
```

## Conclusions / lessons from this session

1. **Unit tests passing ≠ end-to-end works.** Spec 3a had 15 green unit tests when "implementation complete" was declared, but 4 distinct production bugs only surfaced when the agent actually ran against real ROM + real Gemini API. Two of those (warp-transition race, OutfitBoot fire timing) were latent in Spec 2 and never caught because Spec 2's e2e test loaded a savestate that pre-positioned the party past those code paths.

2. **No-savestate policy increases empirical cost but improves coverage.** Removing `FF1_SAVESTATE`-gated tests (per `feedback_no_savestate.md`) means tests must run from genuine title-screen start. That's expensive but exposes real cold-start bugs that savestate-shortcut tests masked. The 3 fixes above (`4defa54`, `d74caff`, `af500d0`) are direct dividends.

3. **Production prompts need empirical iteration against the real model.** The `SYSTEM_OVERWORLD_LANDMARK` prompt had three errors only visible from real Gemini calls: (a) `maxOutputTokens` too small for thinking mode, (b) viewport described as 16×16 when NES is 16×15, (c) party position misstated as (8,8) when actually (8,7). Unit tests with `FakeHaikuConsult` returning fake `Found(11,4)` would never catch these.

4. **Cold-start without landmarks is genuinely hard.** The `discoverWeaponShop` flow assumes prior runs seeded `NPC_SHOPKEEPER` landmarks. From a fresh state, `maxAttempts=1` is too few; `WalkInteriorVision`'s LLM-driven probe may walk into a non-shop tile and never recover. This is upstream of Spec 3a but blocks its end-to-end validation.

5. **Autonomous Anthropic Opus runs cost real money.** Each ~10 min run with executor LLM consumes $1-3 in API tokens. Empirical iteration against full game-flow is not free; budget caps must be tight, and bug-fix iterations should target one issue per run to avoid spending budget on already-known failures.

6. **Agent's empirical run reproduced same failure I hit manually.** When I tried hand-driving FF1 via curl/MCP earlier in the session, I also got stuck navigating around water/forest near Coneria peninsula. The agent ran into the same bridge-finding difficulty. This suggests the BFS pathfinder + adaptive anchor shift work in principle but need either better seeding or coord-targeted interior nav.

## Known concerns to flag in eventual PR

1. **`SYSTEM_OVERWORLD_LANDMARK` prompt is heuristic for chaos shrine recognition** — empirically validated only on negative case (no shrine in viewport). Positive case (shrine present) needs first real run that reaches that viewport.
2. **`WalkOverworldTo` BFS over bridge tile (157, 141)** — assumed walkable per FF1 overworld decoder; first validation run will confirm.
3. **Cold-start shop discovery `maxAttempts=1`** is the upstream blocker for Spec 3a end-to-end validation. Follow-up spec needed: bump cap, or pre-seed shopkeeper landmarks via vision scan, or add deterministic shop-finding via FF1 ROM map data.
4. **Manhattan ≤ 1 adjacency tolerates** the 1-tile Y offset between Gemini's reported coords (real party at tile (8,7)) and `ViewportMap.partyLocalXY=(8,8)` abstraction. Functionally OK; documented in `29b3eee` commit message.

## Next session entry point

**Highest-leverage:** unblock Spec 3a end-to-end validation by fixing cold-start shop discovery. Three options:
- (A) Bump `discoverWeaponShop`'s `maxAttempts` to 4-8 unconditionally; let probe try multiple buildings.
- (B) Pre-seed 4-6 candidate `NPC_SHOPKEEPER` landmarks for Coneria from FF1 disasm map data (deterministic, no run-time vision).
- (C) Replace `WalkInteriorVision` with coordinate-targeted interior nav (Spec 3a known concern #1, deferred).

After unblock: full autonomous run (~10 min, $3-5 budget) should reach BRIDGE decision → BridgePhase → discoverChaosShrine → first positive-case validation of Gemini overworld landmark recognition.

**Lower priority:**
- Open Spec 2 PR (parent: PR #116). Spec 2 cleanup (`9de2e94`) needs to land before Spec 3a PR.
- Open Spec 3a PR (parent: ff1-buy-at-shop after Spec 2 lands).
- Consider committing `HANDOFF.md` updates and any agent-discovered landmarks.

## Run command (with current Main.kt wiring)

```bash
# Pre-seed Coneria TOWN_ENTRY (cold-start otherwise drops boot phase)
cat > ~/.knes/ff1-landmarks.json <<'JSON'
{
  "version": 1,
  "landmarks": [
    {"id":"interior_entry_8_146_152","kind":"TOWN_ENTRY","worldX":146,"worldY":152,
     "mapIdInterior":8,"visited":true,"note":"coneria-town entry","discoveredRunId":"preseed"}
  ]
}
JSON
rm -f ~/.knes/ff1-overworld-warps.json ~/.knes/ff1-blockages.json \
      ~/.knes/ff1-overworld-terrain.json ~/.knes/ff1-interior-memory.json \
      ~/.knes/ff1-ow-memory.json

KNES_VISION=gemini-pro \
  ./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
    --wall-clock-cap-seconds=600 \
    --cost-cap-usd=3.0 \
    --max-skill-invocations=120"
```

Required env: `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`. Without `KNES_VISION=gemini-pro` the boot/bridge phases silently no-op.

Watch traces:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E '"phase":"BOOT"|bridge_phase_summary|bridge_tick' "$LATEST/trace.jsonl"
```

Expected after cold-start fix:
- `boot_outfit_summary: weaponsBought=N weaponsEquipped=M` with N,M ≥ 1
- `[strategy] BRIDGE` after `min_level=3`
- `bridge_phase_summary: reached at (x,y)` with `(x,y)` ≈ ToF entrance
- `OUTCOME: AtGarlandBattle` would be Spec 3b/3c — Spec 3a stops at adjacency.
