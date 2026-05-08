# FF1 Koog Agent — Handoff (Spec 5 town-overlay nav, 2026-05-09)

**Branch HEAD:** `6ff4914` on `ff1-buy-and-equip-coneria` (clean, all session work pushed).
**Open PR:** #121 in `ArturSkowronski/kNES` targeting `master` — <https://github.com/ArturSkowronski/kNES/pull/121>.
**Tests:** 334 unit (3 baseline failures unchanged · 7 gated skipped).

## TL;DR — what landed and what is still broken

This session inherited a Spec 5 boot phase that compiled but had never reached a weapon-shop empirically. After 7 runs (~$2-3 in API spend) we found and fixed 4 distinct bugs in sequence, each surfaced by the previous fix. The agent now reaches the in-game weapon-shop BUY/SELL/EXIT menu — but the runtime rejects entry detection because the architecture wrongly assumes shops are sub-maps. **That single architectural mistake is the only remaining blocker for end-to-end weapon purchase.** Detail in §"Open architectural debt".

## What changed in this session

All landed in commit `6ff4914`:

1. **`readPos` branches on `mapflags bit 0`, not `mapId`.** FF1 NES has THREE coord regimes: genuine overworld (worldX/Y), sub-shop (smPlayerX/Y), AND **town overlay** (mapId=0 + mapflags.0=1, smPlayerX/Y). The third was missing — boot advisor read frozen worldX/Y in town overlay so every tap looked "blocked".
2. **`walk_settle` 30 → 120 frames.** Town transition + sprite render finishes before the advisor takes over. Eliminates run #2's "party not visible" Fail.
3. **3-regime advisor context** (`OVERWORLD` / `TOWN_OVERLAY` / `SUB_MAP`) with regime-appropriate position semantics injected into Gemini's per-iter prompt.
4. **Minimap (V5.38)** — visited-tile set + per-tile blocked-cardinals, 13×13 ASCII grid in advisor context. Breaks myopia (run #5 evidence: with no minimap Gemini circled INN for 40 iters; with minimap reached weapon-shop view in 22).
5. **Action log 6 → 30 + counter-intuitive detour prompt** ("walk away from goal to break out of a wall pocket"). Breaks local pathfinding loops (run #6 evidence: 6-entry log → 38 iters oscillating 3 tiles; 30-entry log → reached BUY menu in iter 22).
6. **Mandatory double-check retry** when advisor bails with party-visibility doubt (defensive; not exercised post-settle bump).
7. **`SYSTEM_ADVISOR` rewritten** with required reasoning order (LOCATE PARTY → LOCATE TARGET → DERIVE DIRECTION) and explicit ban on training-data game geography. Run #1 had Gemini saying "town is south of castle" from atlas-style memory; rewritten prompt forced screenshot-derived reasoning.
8. **`AdvisorAgent.systemPrompt` SUB-GOAL HIERARCHY** — outfit (T glyph, NOT C) → grind → bridge → shrine. Strategic advisor now knows weapons must come before bridge crossing.
9. **Castle short-circuit** in `runOutfitBootPhase` — if `curMapId in {8, 24}` treat as wrong-building immediately, skip vision scan. Saves API cost on accidental warp-into-castle.
10. **Diagnostic dump** — `/tmp/spec5-boot-iter-NN-midM-mfF-posPX_PY-smSX_SY-wWX_WY.png` per boot-advisor iter so RAM-vs-engine state can be cross-verified post-mortem.

## Architecture (post-session)

```
session.run()
├── pre-boot (deterministic, no LLM)
│   └── PressStartUntilOverworld if RAM shows title screen
├── main turn loop
│   ├── observe phase + RAM
│   ├── BOOT TRIGGER (RAM-based, fires once):
│   │   └── if !done && mapId==0 && char1_str>0:
│   │       └── runOutfitBootPhase()
│   │           ├── walk to TOWN_ENTRY landmark waypoint (preseed)
│   │           ├── settle 120 frames (no auto-tap)
│   │           ├── advisor loop (max 80 iters), per iter:
│   │           │   ├── readPos honours mapflags bit 0 (3 regimes)
│   │           │   ├── render minimap (visited+blocked tiles, 13×13)
│   │           │   ├── 30-entry action log → Gemini context
│   │           │   ├── advisor (Gemini 2.5 Pro thinking) returns action
│   │           │   ├── DOUBLE-CHECK retry if Fail with party-visibility doubt
│   │           │   ├── castle short-circuit if mapId in {8,24}
│   │           │   ├── execute action with retry-on-no-movement
│   │           │   └── currently: Done accepted only if mapId != 0
│   │           │     ↑ THIS IS WRONG — see "Open architectural debt"
│   │           ├── post-enter: scan keeper, walk party adjacent
│   │           └── BuyAtShop × 4 chars + EquipWeapon × 4 chars
│   └── strategic LLM loop (Garland goal, etc.)
```

## Empirical trajectory (7 runs this session)

| Run | What it tested | Discovery / failure mode | End-state |
|-----|----------------|--------------------------|-----------|
| #1 | Initial prompt + sub-goal hierarchy + castle guard | Gemini used training-data ("town is south of castle"), all 4 cardinals blocked | Fail 4 iters |
| #2 | Locate-party prompt + Fail escape | Gemini bailed iter 0 with "party not visible" — escape hatch too tempting | Fail 0 iters |
| #3 | Diagnostic dump + assume-(8,7) prompt | Filename mapflags transitions revealed `mapflags=1+mapId=0` = **town overlay**, worldX/Y frozen at entry | Fail 4 iters |
| #4 | (killed, repurposed) | — | Killed |
| #5 | readPos mapflags-aware + 120f settle + 3-regime context + double-check | Movement detected. **Myopia**: Gemini circled INN for 40 iters | OOB 40 iters |
| #6 | Minimap (visited+blocked grid) | Broke myopia, reached weapon-shop view at smPlayer (13,8). **Local pathfinding loop**: 38 iters oscillating 3 tiles around walls | Cap 80 iters |
| #7 | Action log 6→30 + detour prompt | **Reached weapon shop, opened BUY/SELL/EXIT menu in iter 22-23.** Done rejected because `mapId=0` | Killed (architectural bug confirmed) |

Run #7 iter 23 screenshot: full Welcome dialog + Buy/Sell/Exit + party adjacent to shopkeeper + 400G display, all while RAM mapId=0 and mapflags=1. Empirical proof that FF1 NES shops are NPC overlays, not sub-maps.

## Open architectural debt — block on end-to-end weapon purchase

**FF1 NES shops are NPC dialog overlays inside the town overlay (mapflags=1, mapId=0), NOT sub-maps.** The `runOutfitBootPhase` `inSubShop` check (`curMapId != 0`) is structurally wrong:

```kotlin
val inSubShop = curMapId != 0 && curMapId != initialMapId  // ← never true for shops
```

Run #7 iter 23: Gemini correctly emits `Done` with reason *"screenshot shows party is inside the WEAPON shop, confirmed by 'WEAPON' label and shopkeeper sprite"*. Runtime rejects: *"still on starting map (mapId=0) — Done only valid after entering a sub-shop"*. Then iters 24-28 spam `Done`, each rejected, ~$0.13 wasted.

**Fix needed (next session):**
1. **Shop-UI detection** — replace mapId check with one of:
   - **Vision** — Pass-1 scan for BUY/SELL/EXIT menu pattern (current `outfitVision.scanInteriorCandidates` could be extended).
   - **RAM** — `screenState` / `menuCursor` registers may flip when shop dialog opens (verify in profiles + Disch disasm).
   - Both, with vision as primary and RAM as confirmation.
2. **`BuyAtShop` precondition** — allow `mapId=0 && mapflags=1` (NPC-in-town-overlay), not just `mapId == landmark.mapId`.
3. **Done semantics** — accept Done iff shop-UI detected, regardless of mapId.

Estimated work: 2-4 hours including verification run.

## What's uncommitted

```
?? .claude/scheduled_tasks.lock
```

Just the local Claude scheduler lock. Nothing else — all session work landed in `6ff4914`.

## Run command (for next-session diagnostic re-run if needed)

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

Watch trace:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'boot_walk_settle|boot_advisor\[|boot_advisor_done|boot_advisor_castle|boot_purchase|boot_outfit_summary' "$LATEST/trace.jsonl"
```

Diagnostic screenshots accumulate in `/tmp/spec5-boot-iter-NN-midM-mfF-posPX_PY-smSX_SY-wWX_WY.png` — filename encodes the position regime + raw RAM, so post-mortem can confirm readPos branched correctly.

## Lessons (carried forward — also in `~/.claude/projects/.../memory/`)

1. **Locate party first when prompting vision LLMs.** Run #1 fail mode: Gemini chose direction from training-data atlas ("town is south of castle") instead of looking at the screenshot. Prompt MUST enforce `(1) locate party (2) locate target (3) derive direction` order and explicitly ban game-geography priors. Saved as `feedback_locate_party_first.md`.
2. **mapflags bit 0 = "in standard map" (Disch disasm) — not mapId.** The same mapId can mean overworld OR town overlay depending on mapflags. Any code reading "party position" must branch on mapflags, not mapId. Bug latent for ≥2 spec iterations until run #3 diagnostic dump caught it.
3. **Settle frames matter for vision.** 30 frames was too few for FF1 town transition; party sprite hadn't fully rendered when advisor first looked. 120f is comfortable. Cheap insurance against "vision fails on mid-render frame".
4. **Action log size determines whether LLM can self-correct from local minima.** 6 entries: Gemini oscillates 3-tile wall pocket for 38 iters. 30 entries: Gemini sees the cycle and tries detour. The next escalation (if 30 isn't enough) is deterministic BFS fallback when stuck-detection fires — designed but not implemented this session.
5. **Minimap > extended action log alone.** 2D structured visited+blocked map gives Gemini geometric reasoning the linear log can't. The two are complementary, not redundant.
6. **In-game shop semantics ≠ map-engine semantics.** FF1 shops are dialog overlays, not sub-maps. Don't infer engine state from gameplay metaphors — read the disassembly or empirically verify.

## Carried-over principles

- **Autonomy:** agent gra grę; dev nie. Per `autonomy_principle.md`. Manual gameplay-recorded paths still off-limits.
- **No-savestate persistence:** new specs nie używają savestate-hash-keyed flags ani FF1_SAVESTATE-gated e2e tests; persistence flows through `landmarkMemory`. Per `feedback_no_savestate.md`.
- **Locate-party-first vision prompts:** see lesson #1 above. Per `feedback_locate_party_first.md`.
