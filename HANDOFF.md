# FF1 Koog Agent — Handoff (Spec 5 — 4/4 buy MERGED, next: exit + grind, 2026-05-09)

**Master HEAD:** `361e88e` (merge of PR #122).
**Branch:** `ff1-buy-and-equip-coneria` (still active for follow-up work).
**Tests:** 348 unit + 3 new in `SavestateRoundtripDebug` (round-trip identity, Main.kt-flow regression, manual diagnostic).

## TL;DR — milestone reached: 4/4 weapon purchase, end-to-end

Three architectural wins this session:

1. **First-ever 4/4 character purchase** (Run B2-v3, 2026-05-09 14:59Z). Vision-advisor reads each sub-screen and decides the next single tap; class-aware item selection; 77 advisor calls, ~\$1.0 spend, 45G in-game.
2. **vNES NameTable.stateSave bug fixed.** Leftover debug-print guard nested the per-tile `putByte` loop body inside an effectively-never-true condition, so save wrote ~0 bytes per nametable while load read full `width*height` — corrupting PPU snapshots for every MMC1 + non-MMC1 game. Round-trip identity test added.
3. **Savestate runtime handling.** Pre-warm 120 frames before `loadState` + post-pump 120 after (PPU pipeline must engage); skip walk + nav-advisor when `KNES_FF1_LOAD_SAVESTATE` is set (savestate already places us in shop). Without these, RAM restored fine but framebuffer rendered gray / title.

## What landed this continuation (PR #122, 2 commits)

1. `631e5e1` — `fix(emulator)`: NameTable.stateSave wrote 0 bytes, corrupted PPU snapshots. Round-trip identity test added.
2. `8fdce68` — `feat(spec5)`: full 4/4 character weapon purchase via savestate + advisor. Stack:
   - Main.kt: 120-frame pre-warm before loadState + 120-frame post-pump.
   - AgentSession.runOutfitBootPhase: skip walk-to-coneria + nav-advisor when `KNES_FF1_LOAD_SAVESTATE` set.
   - `maxAdvisorCalls` 50 → 120.
   - SYSTEM_SHOP_PURCHASE prompt: POST-PURCHASE FLOW section (cursor reset to char1 between buys, counted-Down recipe), ERROR_DIALOG handling, mid-subflow Done guardrail.
   - Per-iter advisor screenshot dump in BuyAtShop (`/tmp/spec5-buy-advisor-iter-NN-served-XXXX.png`).

## Empirical milestone

Run B2-v3 (2026-05-09 14:59Z): **4/4 chars BOUGHT in single run.**
```
boot_savestate_skip_walk_nav: KNES_FF1_LOAD_SAVESTATE set, skipping walk-to-coneria + nav advisor
boot_post_enter_detect: open=false source=vision_unknown kind=null phase=MAIN_MENU phaseSaysOpen=true
char1 BOUGHT iter=8   (Fighter,    Small Knife,     5G)
char2 BOUGHT iter=41  (Thief,      Small Knife,     5G)
char3 BOUGHT iter=65  (BlackBelt,  Wooden Nunchuck, 10G)
char4 BOUGHT iter=77  (RedMage,    Rapier or Hammer, 25G)
boot_outfit_summary: weaponsBought=4 weaponsEquipped=0 totalGoldSpent=45
```

Run-by-run progression toward 4/4:

| Run  | Bought | Notes                                                              |
|------|--------|--------------------------------------------------------------------|
| #21, #24 (pre-fixes)                                | 2/4 | cap=50, FOR_WHOM cursor confusion                                  |
| Run A2 (post-NameTable, fresh nav, cap=80)          | 3/4 | char3 finished iter 75, char4 starved (cap exhausted)              |
| **Run B2-v3 (post-NameTable + savestate runtime, cap=120)** | **4/4** | char1@i8, char2@i41, char3@i65, char4@i77; ~\$1.0 advisor spend |

## Next goal: post-purchase exit + grind phase

User-defined next milestone (2026-05-09 cont 2): **after buy, exit shop and start grind.**

Skipping EquipWeapon for now — chars can grind bare-handed (lower DPS but works). Equip is a separate follow-up.

Subgoals (concrete, in order):

1. **Exit shop dialog cleanly post-buy.** Today the boot phase ends at `boot_outfit_summary: weaponsBought=4`, control returns to strategic loop. Need to verify `ExitInterior` (or B-spam to dismiss BUY/SELL/EXIT → land back on town overlay) actually walks the party out of Coneria town and back to the overworld at world(147,155) or nearby. Likely already partially wired via `ExitInterior` skill — need to confirm + add a post-buy exit step in `runOutfitBootPhase`.
2. **Walk to grind tile / encounter area.** Coneria is surrounded by grasslands south + east. Strategic loop already has GRIND/REST/BRIDGE token routing (visible in earlier Run B2 trace `output:"raw=GRIND parsed=GRIND"`). Need: after exit, agent should enter encounter zone and trigger random battle.
3. **Win battle (≥1 fight).** `battleFightAll` skill exists in `knes-debug/src/main/resources/profiles/ff1.json`. Mostly tap-A through. Should mostly work even with no weapons equipped (chars do bare-hand damage).
4. **Track XP gain → level-up.** Strategic loop's GRIND target `min_level >= 3 before BRIDGE` already in place — just needs the exit + walk-to-grass to actually fire.

## Architecture (post-merge)

```
session.run()
├── pre-boot
│   ├── if KNES_FF1_LOAD_SAVESTATE set
│   │   ├── advanceFrames(120)         ← V5.46.5 pre-warm (PPU engagement)
│   │   ├── session.loadState(file)
│   │   └── advanceFrames(120)         ← V5.46.5 post-pump (re-render)
│   └── else → PressStartUntilOverworld if RAM shows title screen
├── main turn loop
│   ├── observe phase + RAM
│   ├── BOOT TRIGGER (RAM-based, fires once):
│   │   └── if !done && mapId==0 && char1_str>0:
│   │       └── runOutfitBootPhase()
│   │           ├── if savestateLoaded: skip walk + advisor   ← NEW
│   │           ├── else walk to TOWN_ENTRY landmark + 120f settle
│   │           ├── (if !savestateLoaded) advisor loop (max 80 iters)
│   │           ├── post-enter detect (ShopUiDetector + classifyShopMenuPhase)
│   │           ├── BuyAtShop.invokeWithAdvisor (cap=120, per-iter screenshots)
│   │           └── ExitInterior + EquipWeapon × 4 chars   ← NEXT: validate exit
│   └── strategic LLM loop                                  ← NEXT: GRIND token
```

## Run command (current — buy then strategic loop)

```bash
# Fresh nav with NameTable fix produces a fresh-format savestate post-buy.
rm -f ~/.knes/ff1-*.json /tmp/spec5-shop-entered.savestate
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
    --wall-clock-cap-seconds=900 --cost-cap-usd=3.0 --max-skill-invocations=120"

# Once savestate exists, fast-iterate buy+exit+grind via:
KNES_VISION=gemini-pro KNES_FF1_LOAD_SAVESTATE=/tmp/spec5-shop-entered.savestate \
  ./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
    --wall-clock-cap-seconds=600 --cost-cap-usd=3.0 --max-skill-invocations=120"
```

Watch trace:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'boot_savestate|BOUGHT|boot_purchase_advisor_done|boot_outfit_summary|boot_equip|boot_exit|GRIND|BATTLE' "$LATEST/trace.jsonl"
```

Per-iter advisor frames in `/tmp/spec5-buy-advisor-iter-*.png` for post-mortem.

## Lessons (carried forward)

- **Vision-advisor > deterministic state machines** for FF1 NES UI navigation (validated 4/4). Cap=120 + POST-PURCHASE prompt section. Pre-fix state machine: 0/4. Pre-fix advisor: 2/4. Post-fix advisor: 4/4.
- **Savestate runtime needs PPU warm-up.** 120-frame pre-warm before loadState + 120-frame post-pump. 0-frame: RAM resets. 1-frame: RAM sticks but renderer gray. 120-frame: both correct.
- **vNES NameTable.stateSave was silently broken since the original Java port** — caught by round-trip identity test. Worth running similar round-trip tests for other Memory subclasses.
- **When KNES_FF1_LOAD_SAVESTATE is honoured, skip walk + nav advisor.** Walking presses cardinals on the active shop dialog and the resulting B-press exit chain landed the agent on title menu with `char_str=0` after PressStartUntilOverworld kicked in to "fix" it.
- **Class-aware item picking + counted-Down FOR_WHOM** = reliable multi-char buy. Each new BUY_CONFIRM resets cursor to char1; Party state tags ("served"/"NEEDS WEAPON") let the advisor count Downs from char1 to next unserved.

## Carried-over principles

- **Autonomy:** agent gra grę; dev nie. Per `autonomy_principle.md`. Savestate dumping is dev-tool only — captured by agent-driven nav.
- **No-savestate persistence:** new specs nie używają savestate-hash-keyed flags ani FF1_SAVESTATE-gated e2e tests. Per `feedback_no_savestate.md`. (Savestate dev-tool here is for iteration speed, not for unit-test fixtures.)
- **Vision-primary UI detection:** see `feedback_vision_primary.md`.
- **Locate-party-first vision prompts:** still in effect. Per `feedback_locate_party_first.md`.
- **FF1 NPCs move:** action log "blocked" entries time-decay. Per `reference_ff1_npcs_move.md`.
- **Per-iter buy screenshots:** dumped to `/tmp/spec5-buy-advisor-iter-NN-served-XXXX.png`. Per `feedback_buy_screenshots.md`.
