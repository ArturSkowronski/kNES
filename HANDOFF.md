# FF1 Koog Agent — Handoff (Spec 5 advisor v3 + holistic cleanup, 2026-05-08)

**Branch HEAD:** `a1a4f07` on `ff1-buy-and-equip-coneria` (uncommitted local changes from this session — see "What's uncommitted").
**Open PR:** #120 (Spec 5 WIP) targeting `ff1-bridge-and-walk-to-tof`.
**Tests:** 335 unit (3 baseline failures unchanged · 7 gated skipped) — verified after holistic cleanup.

## TL;DR — what changed and why it matters

This session inherited a stuck Spec 5: the agent kept "entering Coneria town" but never bought weapons. After an empirical screenshot dump, the **user identified that mapId=8 is Coneria CASTLE, not Coneria Town**. Every prior session's $70+ of empirical validation was running inside the castle without ever finding shops.

Once that fact was on the table, the architecture was rewritten end-to-end:

- **Coneria's town buildings live on the OVERWORLD** (mapId=0), not in any "town interior" map. Each shop is its own sub-map entered via a specific door tile in the overworld.
- The advisor was rewritten to navigate the overworld by reading building **signs** (sword/dagger icon = weapon shop) and walking onto the matching door, then verifying entry by `currentMapId` changing 0 → some shop sub-mapId.
- Boot phase trigger was hardened: deterministic `pressStartUntilOverworld` runs before the LLM loop, and the boot fire condition is now RAM-based (`mapId=0 && char1_str>0`) instead of FfPhase-classification-based, so it can't be missed by the LLM batching `pressStart + walkOverworldTo` into a single turn.
- Dead code removed: v1 `EnterConeriaWeaponShop` hardcoded sweep, the broken `Coneria8WalkScreenshotTest` (loaded title-screen savestate fixture that's corrupt on this branch), and the now-unused `discoverWeaponShop` / `buildWalkInteriorVision` helpers + their imports.
- Walk-to-Coneria reactive auto-tap was deleted: the cardinal-direction nudge after `walk_settle` was the mechanism that tripped the party onto the castle warp at world (146, 152). With the new architecture the advisor handles deliberate entry; no reactive nudge.

## Architecture (post-cleanup)

```
session.run()
├── pre-boot (deterministic, no LLM)
│   ├── if RAM shows title screen (char1_str == 0):
│   │   └── PressStartUntilOverworld()
│
├── main turn loop
│   ├── observe phase + RAM
│   ├── BOOT TRIGGER (RAM-based, fires once):
│   │   └── if !done && mapId==0 && char1_str>0:
│   │       └── runOutfitBootPhase()
│   │           ├── walk to TOWN_ENTRY landmark waypoint (preseed)
│   │           ├── settle 30 frames (no auto-tap)
│   │           ├── advisor loop (max 80 iters):
│   │           │   ├── read worldX/Y on overworld OR smPlayerX/Y in sub-map
│   │           │   ├── pass screenshot + action log + position to Gemini 2.5 Pro thinking
│   │           │   ├── execute returned action with retry-on-no-movement
│   │           │   ├── on mapId change 0→non-zero: verify shopkeeper, else exit-and-retry
│   │           │   └── Done is rejected on the starting map; only valid in sub-shop
│   │           ├── post-enter: scan keeper, walk party adjacent
│   │           └── BuyAtShop × 4 chars + EquipWeapon × 4 chars
│   └── strategic LLM loop (Garland goal, etc.)
```

Removed from the diagram on this branch:
- `discoverWeaponShop` (Spec 4 InteriorExplorer wrapper) — was for sub-shop interior scanning, not needed when shops are picked by sign on overworld.
- v1 `EnterConeriaWeaponShop` skill — hardcoded sweep, never a good fit.
- `buildWalkInteriorVision` helper — only consumer was `discoverWeaponShop`.

## What's uncommitted

```
M HANDOFF.md
M knes-agent/src/main/kotlin/knes/agent/Main.kt
M knes-agent/src/main/kotlin/knes/agent/explorer/AnthropicHaikuConsult.kt
M knes-agent/src/main/kotlin/knes/agent/explorer/GeminiVisionConsult.kt
M knes-agent/src/main/kotlin/knes/agent/explorer/HaikuConsult.kt
M knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt
D knes-agent/src/main/kotlin/knes/agent/skills/EnterConeriaWeaponShop.kt
?? knes-agent/src/test/kotlin/knes/agent/perception/Coneria8DoorDumpTest.kt
```

`./gradlew :knes-agent:compileKotlin` is clean. `./gradlew :knes-agent:test` matches baseline (335 / 3 baseline-failures / 7 gated-skipped).

## Empirical run state

- **9 runs** this session, ~$8.5 total cost.
- Last run (#9) attempted the v3 architecture but never reached the boot phase: the LLM combined `pressStartUntilOverworld + walkOverworldTo(146, 150)` in a single turn 1 plan, party walked through the castle warp at world (146, 152), then spent 25 minutes oscillating between mapId=8 (castle) and mapId=24 (castle hall). The deterministic pre-boot added in this cleanup eliminates that exact failure mode for the next run.
- v3 architecture is **empirically unvalidated**. The next run is the first opportunity to see whether vision-driven sign-recognition + door-tile walk + sub-shop verify actually reaches a weapon shopkeeper. Expected cost: ~$1–2.

## Run command

```bash
rm -f ~/.knes/ff1-*.json
cat > ~/.knes/ff1-landmarks.json <<'JSON'
{"version":1,"landmarks":[
  {"id":"interior_entry_147_155","kind":"TOWN_ENTRY","worldX":147,"worldY":155,
   "visited":true,"note":"coneria-town overworld waypoint","discoveredRunId":"preseed"},
  {"id":"weapon_shopkeeper_preseed","kind":"NPC_SHOPKEEPER","visited":false,
   "note":"kind=weapon; preseed; items=staff:5,dagger:5,nunchuck:10,rapier:10,hammer:10","discoveredRunId":"preseed"}
]}
JSON

KNES_VISION=gemini-pro ANTHROPIC_API_KEY=... GEMINI_API_KEY=... \
  ./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
    --wall-clock-cap-seconds=900 --cost-cap-usd=2.0 --max-skill-invocations=120"
```

The `worldY=155` waypoint was confirmed by the user as a passable overworld tile in the Coneria-town building cluster, reachable from spawn (146, 158) by 3× Up + 1× Right. The advisor takes over from there.

Watch traces:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'boot_walk_settle|boot_advisor|boot_purchase|boot_outfit_summary' "$LATEST/trace.jsonl"
```

## Diagnostic tools added

- **`Coneria8DoorDumpTest`** (kept) — decodes `mapId=8` from ROM via `InteriorMapLoader`, prints all STAIRS (0x44) tiles, ASCII layout, and hex tile-id dumps. Confirmed that mapId=8 has exactly one STAIRS at (12, 18) (the warp to mapId=24 inner castle), and "shop-like" tile patterns at Y=11..14 and Y=27..31 — but per user inspection these are all castle-interior decoration, not town shops.

## Lessons

1. **A single human-eyeball check on a screenshot collapsed ~$70 of empirical validation.** Every prior assumption about Coneria layout came from LLM advisor reasoning over screenshots, never independently verified. The "stuck in Left loop" failure mode that motivated the whole session turned out to be a context-quality bug, but the architectural premise underneath it was wrong from the start.
2. **FfPhase classification at turn boundary is not a reliable trigger for one-shot boot phases.** When the LLM batches multiple tools into a single turn, intermediate phase observations don't fire boundary-keyed triggers. RAM-based triggers (`mapId == 0 && char1_str > 0`) are independent of LLM scheduling and fire correctly.
3. **Auto-cardinal-tap "settle" logic causes more problems than it solves.** It was added to overcome RAM/tile timing races, but in practice it ended up tripping the party onto warp tiles next to the intended waypoint. The advisor (with explicit retry-on-no-movement) handles timing properly without needing reactive nudging at the skill layer.

## Carried-over principles

- **Autonomy:** agent gra grę; dev nie. Per `autonomy_principle.md`. Manual gameplay-recorded paths still off-limits.
- **No-savestate persistence:** new specs nie używają savestate-hash-keyed flags ani FF1_SAVESTATE-gated e2e tests; persistence flows through `landmarkMemory`. Per `feedback_no_savestate.md`.
