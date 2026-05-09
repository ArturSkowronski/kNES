# FF1 Koog Agent — Handoff (Spec 5 cont. — vision advisor purchase 2/4, 2026-05-09)

**Branch HEAD:** `fb46d3c` on `ff1-buy-and-equip-coneria`. Subsequent local mapper fixes still in place.
**Open PR:** #121 in `ArturSkowronski/kNES` targeting `master` — <https://github.com/ArturSkowronski/kNES/pull/121>.
**Tests:** 348 unit (3 baseline failures unchanged · 7 gated skipped).

## TL;DR — class-aware vision advisor working; navigation flaps; savestate fix architectural

Two big architectural moves landed this continuation, plus one open question:

1. **Spec 5 architectural blocker (NPC-overlay shops) is closed.** Run #5 + #11 + #13 + #21 + #24 all made successful weapon purchases. char1 (Fighter) gets Small Knife reliably; char2 (Thief) typically follows. Bought = up to 2/4 per run with current cap.
2. **Vision-advisor-driven shop nav** replaces the brittle deterministic state machine — Gemini reads sub-screen + cursor at each step, decides next single tap. Cost ~$0.4 per run inside the shop, way more reliable than tap-counts.
3. **MMC1 savestate fix** committed but not empirically validated yet. Loading a pre-fix savestate drifts back to title screen because MMC1 internal registers weren't serialized. Theoretical fix is in place; needs a post-fix successful nav to dump + reload.

26 runs ran today, ~$10-12 API spend. Gemini nav advisor success rate today ≈ 30% (10 successes vs 16 timeouts/oscillation/api-error). All purchase-side wins came on the successful navs.

## What landed this continuation session (8 commits)

In order:

1. `18e58cd` — `ShopUiDetector` (vision-primary, RAM negative gate). Replaces `inSubShop = mapId != 0` with `mapflags.bit0=1` recognition. `BuyAtShop` precondition relaxed for town-overlay. SYSTEM_ADVISOR rewritten.
2. `7bd2a91` — `BuyAtShop.menuAlreadyOpen` flag — skip the dialog-opening A-tap when shop UI already drawn at acceptance time.
3. `86faf9b` — kind-aware Done acceptance: require `kind == "weapon"` (rejects accidental armor-shop entries with feedback to advisor's action log).
4. `16f33a5` — handoff update declaring Spec 5 architectural blocker closed (run #5 milestone).
5. `a1086ea` — per-call menu probe + 5-B exit (intermediate iteration; superseded by stateful + advisor approaches).
6. `40012ce` — SYSTEM_ADVISOR prompt: town NPCs move, blocked entries time-decay (require recent confirmation in TOWN_OVERLAY).
7. `5f4493d` — vision-classified shop menu state machine (intermediate; partially superseded by full advisor).
8. `1ed46ba` — **vision-advisor-driven shop purchase**. Replaces state machine with per-step Gemini advice. Adds `char1_class..char4_class` registers to ff1.json profile (offsets `$6100/$6140/$6180/$61C0` per Disch disasm). New `HaikuConsult.adviseShopPurchase` method + `SYSTEM_SHOP_PURCHASE` prompt teaching all eight FF1 shop sub-screens + per-class equip rules.
9. `91a1074` — accept Done iff EITHER `kind=weapon` OR `phase != CLOSED` (run #19 trace: nav reached BUY/SELL/EXIT but kind classifier returned null on same screen — dual classifier saves the entry).
10. `7ce67e6` — savestate dump on `entered=true` to `/tmp/spec5-shop-entered.savestate`, env var `KNES_FF1_LOAD_SAVESTATE=<path>` to skip pressStart + advisor nav for dev iteration. `EmulatorToolset.session` was private, now public.
11. `4f8a2b4` — bumped `maxAdvisorCalls` 30 → 50 (run #21 hit cap mid-purchase of char3).
12. `fb46d3c` — **MMC1 + MapperDefault savestate bugs**. MapperMMC1 had no `stateSave`/`stateLoad` override → internal registers (shiftRegister, regControl, regCHR0/1, regPRG) defaulted to power-on values after load → ANY subsequent bank switch after restore corrupted PRG mapping → title-screen drift. Plus MapperDefault.mapperInternalStateLoad/Save bodies were SWAPPED (Load wrote, Save read).

## Empirical results (this session)

| Run  | Phase reached                   | Result                                                  |
|------|---------------------------------|---------------------------------------------------------|
| #5   | nav OK, shop OK                 | char1 bought Wooden Staff (5G). First ever purchase.    |
| #6   | nav stuck 80 iters              | $1.45, no entry                                         |
| #7   | nav api-error iter 8            | Fail                                                    |
| #8   | nav OK, shop OK                 | char1 Bought                                            |
| #11  | nav OK, shop OK                 | char1 Bought (5-B exit fix verified for char1)          |
| #13  | nav OK, shop OK                 | char1 Bought + char2 may have bought silently (13G)     |
| #15  | nav OK, post-enter scrambled    | All 4 ShopClosed                                        |
| #16  | nav OK, post-enter scrambled    | All 4 ShopClosed                                        |
| #17-20| nav api-error / oscillation    | various Fail                                            |
| **#21**| **nav OK, vision advisor**    | **char1 (Knife) + char2 (Knife) Bought, 10G.** Hit cap=30 mid-char3.  |
| #22  | savestate-load test             | Title screen — pre-fix savestate                        |
| #23-26| various nav fails             | mostly $1.45 stuck-loop; #24 was vision advisor success |
| **#24**| **nav OK, vision advisor**    | **char1 + char2 Bought, 20G.** Cap=50 reached during char3/char4 nav confusion. |

## Architecture (post-session)

```
session.run()
├── pre-boot
│   ├── if KNES_FF1_LOAD_SAVESTATE set → session.loadState(file) and skip pressStart
│   └── else → PressStartUntilOverworld if RAM shows title screen
├── main turn loop
│   ├── observe phase + RAM
│   ├── BOOT TRIGGER (RAM-based, fires once):
│   │   └── if !done && mapId==0 && char1_str>0:
│   │       └── runOutfitBootPhase()
│   │           ├── walk to TOWN_ENTRY landmark
│   │           ├── settle 120 frames
│   │           ├── advisor loop (max 80 iters) — Gemini decides next nav tap
│   │           │   └── on Done: dual-classifier (kind=weapon OR phase != CLOSED) → entered=true
│   │           ├── on entered=true: dump savestate, post-enter detect
│   │           ├── if menuAlreadyOpen → skip keeper-approach
│   │           ├── BuyAtShop.invokeWithAdvisor:
│   │           │   ├── per-iter context: char classes from $6100..$61C0 + remaining gold + served list
│   │           │   ├── Gemini decides next single tap (Up/Down/Tap_A/Tap_B/Done/Fail)
│   │           │   └── delta-tracks gold + per-char weaponSum to mark "Bought"
│   │           └── ExitInterior + EquipWeapon × 4 chars
│   └── strategic LLM loop
```

## Remaining work

### Validated direction, needs more iter cap

**4/4 char buy in single run.** Run #24 trace shows the advisor's Gemini gets confused by FOR_WHOM cursor positioning across multiple purchases — cursor returns to char1 each time, advisor needs to Down past served chars. With cap=50, advisor reaches char3 attempt but runs out of iter when navigating FOR_WHOM. Two complementary fixes:
- Bump cap to 80 (~1.5x cost, ~$0.6/run).
- Improve `SYSTEM_SHOP_PURCHASE` prompt: explicitly teach that after each purchase, FOR_WHOM cursor resets to char1 and the advisor needs to count Downs from char1 to target. Also: encourage the advisor to use the `served` flag in context to skip already-served chars.

### Architectural: not yet validated empirically

**Savestate restore still drifts to title.** Despite MMC1 fix (`fb46d3c`), runs that load /tmp/spec5-shop-entered.savestate land at title menu. Possible causes (each requires investigation):
- Old savestate files were written before mapper fix; need a fresh nav success → dump → load to verify. Today's clean-rebuild + run #26 nav failed before dump.
- `ByteBuffer.getBytes()` returns the full buf array (size 397569 stable across runs), not curPos. Padding zeros at end of file. Save/load alignment is what matters; could the truncation handling cause issues?
- PPU state save may not capture chr-rom bank pointers (CHR-ROM/RAM mode). Worth checking PPU.stateSave/stateLoad for completeness with respect to MMC1's CHR bank registers.

**EquipWeapon MenuStuck.** Earliest evidence run #5: `60 taps without equipped-flag transition`. Skill drives in-menu equip flow but the equipped-flag (high bit of `char{N}_weapon{slot}`) never flips. Same brittle-state-machine pattern that the BUY side hit; same fix likely applies — replace with a vision-advisor `adviseEquip` method.

### Misc / lower priority

- Strategic-loop budget pressure post-boot (`maxSkillInvocations=120` cap consumed by strategic Anthropic LLM after boot succeeds).
- Fanslated-ROM dialog noise ("No Shit." text observed at smPlayer (10,11)) — not a code issue, advisor occasionally misreads; current dual-classifier neutralises most cases.

## Run command (next-session)

```bash
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
```

If a fresh successful nav dumps `/tmp/spec5-shop-entered.savestate`, validate the savestate fix:

```bash
KNES_VISION=gemini-pro KNES_FF1_LOAD_SAVESTATE=/tmp/spec5-shop-entered.savestate \
  ./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes \
    --wall-clock-cap-seconds=600 --cost-cap-usd=2.0 --max-skill-invocations=80"
```

Expected on successful load: skip pressStart, boot phase trigger fires immediately, post-enter detect sees shop UI, vision advisor runs purchase loop. If still drifts to title menu: the mapper fix isn't sufficient; investigate PPU CHR-bank register serialization next.

Watch trace:
```bash
LATEST=$(ls -td ~/.knes/runs/*/ | head -1)
grep -E 'boot_advisor_done_verify|boot_advisor_summary|boot_savestate|boot_purchase_advisor|boot_purchase|boot_equip|boot_outfit_summary|loaded savestate' "$LATEST/trace.jsonl"
```

Diagnostic dumps in `/tmp/spec5-buy-*.png` for screen state at each purchase step.

## Lessons (carried forward)

- **Vision-advisor beats deterministic state machines for FF1 NES UI navigation.** Run #21 + #24 were the first successful buys after we replaced tap-count guessing with `adviseShopPurchase`. The state machine version (commits `5f4493d` → `a232050`) bought 0/4 across multiple runs. The advisor version bought 2/4 reliably and would do 4/4 with more iter cap.
- **Two independent vision classifiers reduce stochastic flips.** Run #19 had nav reach BUY/SELL/EXIT but kind classifier returned null on the same screen. Adding phase classifier as a fallback (`91a1074`) recovered ~$1.40 wasted runs.
- **vNES MMC1 savestate had multiple latent bugs** (`fb46d3c`). Architectural fix posted; needs empirical validation. If you fix savestate next, the dev iteration loop on shop-side bugs becomes 30s instead of 15min.
- **Class-aware item picking works** — char_class register at $6100/$6140/$6180/$61C0 + per-class equip rules in advisor prompt = advisor correctly picks Knife for Fighter, Knife for Thief, Nunchuck for Black Belt (run #21 trace).

## Carried-over principles

- **Autonomy:** agent gra grę; dev nie. Per `autonomy_principle.md`. Savestate dumping is dev-tool only — captured by agent-driven nav, not hand-recorded.
- **No-savestate persistence:** new specs nie używają savestate-hash-keyed flags ani FF1_SAVESTATE-gated e2e tests; persistence flows through `landmarkMemory`. Per `feedback_no_savestate.md`. (The savestate dev-tool here is for iteration speed, not for unit-test fixtures.)
- **Vision-primary UI detection:** see `feedback_vision_primary.md`.
- **Locate-party-first vision prompts:** still in effect. Per `feedback_locate_party_first.md`.
- **FF1 NPCs move:** action log "blocked" entries time-decay. Per `reference_ff1_npcs_move.md`.
