# FF1 Koog Agent — Handoff (Spec 5 shop-architecture closed, 2026-05-09 cont.)

**Branch HEAD:** `86faf9b` on `ff1-buy-and-equip-coneria` (all session work pushed).
**Open PR:** #121 in `ArturSkowronski/kNES` targeting `master` — <https://github.com/ArturSkowronski/kNES/pull/121>.
**Tests:** 348 unit (3 baseline failures unchanged · 7 gated skipped).

## TL;DR — Spec 5 architectural blocker closed end-to-end

The previous handoff documented the FF1 NPC-overlay shop architecture mismatch as the sole remaining blocker. **That blocker is now closed empirically.** Run #5 (2026-05-09) made the first successful weapon purchase in project history:

```
boot_advisor_done_verify[23]: open=true source=vision_kind=weapon kind=weapon
boot_post_enter_detect: open=true kind=weapon
boot_keeper_approach: skipped — weapon shop UI already open; menuAlreadyOpen=true
boot_purchase: char1 slot=0 result=Bought: cost=5 char=1 slot=0 weaponSum 0->3
boot_outfit_summary: weaponsBought=1 weaponsEquipped=0 totalGoldSpent=5
```

Two new bugs surfaced behind the architectural fix:
1. Per-character slot mapping is class-naive (char1 happened to fit slot 0; char2/3/4 fail WrongClass on every retry).
2. `EquipWeapon` skill: `MenuStuck: 60 taps without equipped-flag transition`.

Neither is a regression — both were latent and only became reachable now that BUY actually executes. They are listed in §"Remaining work" below.

## What changed this continuation session

Three commits on top of `6ff4914`:

1. **`18e58cd` — `ShopUiDetector` (vision-primary, RAM negative gate).** New `knes/agent/perception/ShopUiDetector.kt`. RAM rules out impossible regimes (overworld with `mapflags.bit0=0`, castle `mapId in {8,24}`, battle `screenState != 0`); vision (`HaikuConsult.classifyShopMenu`) is the definitive positive signal. Replaces the structurally wrong `inSubShop = curMapId != 0 && curMapId != initialMapId` heuristic in `runOutfitBootPhase`. `BuyAtShop` precondition relaxed to accept `mapId=0 + mapflags.bit0=1` (town overlay) or `mapId>0` (legacy sub-shop). Landmark lookup falls back to kind-from-note when `mapId=0`. `SYSTEM_ADVISOR` prompt rewritten: shops are NPC dialog overlays, mapId stays 0, Done is valid whenever BUY/SELL/EXIT is visible. +11 ShopUiDetector tests, +2 BuyAtShop tests.

2. **`7bd2a91` — `BuyAtShop.menuAlreadyOpen` flag.** Run #3 reached BUY/SELL/EXIT but all 12 purchase attempts failed because the post-enter B-spam ×4 left the dialog stack misaligned and BuyAtShop's two opening A-taps collapsed into one — every Down/A nav was off-by-one, silently buying wrong-class items. Fix: when `ShopUiDetector` reports the shop UI is already open at post-enter, `BuyAtShop` is invoked with `menuAlreadyOpen=true`; it skips the dialog-opening A-tap and forces cursor back to BUY via Up×2 before item selection. The B-spam was removed. +1 BuyAtShop test.

3. **`86faf9b` — kind-aware Done acceptance.** Run #4 walked into the ARMOR shop (sign visually similar). The detector said `open=true kind=armor` and the runtime accepted Done because any shop kind passed the open check; BuyAtShop tried to buy weapons from an armor shop, all 12 failed WrongClass. Fix: at Done acceptance and at post-enter, also require `detection.kind == "weapon"`. When a different shop kind opens, B-spam exits the dialog stack, the wrong-shop event lands in the action log as `Done(rejected-wrong-shop=armor)` so Gemini learns to pick a different building, and the existing 3-strike `enteredWrongBuildingCount` give-up bounds the loop.

Total session API spend ~$2 (5 runs).

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
│   │           ├── settle 120 frames
│   │           ├── advisor loop (max 80 iters), per iter:
│   │           │   ├── readPos honours mapflags bit 0 (3 regimes)
│   │           │   ├── render minimap, 30-entry action log, advisor consult
│   │           │   ├── castle short-circuit if mapId in {8,24}
│   │           │   ├── execute action with retry-on-no-movement
│   │           │   └── on Done: ShopUiDetector.detect(ram, screenshot, vision)
│   │           │       ├── kind="weapon"  → entered=true, break
│   │           │       ├── kind="armor"…  → wrong-shop, B-spam exit, continue
│   │           │       └── open=false     → action log entry, continue
│   │           ├── post-enter ShopUiDetector check:
│   │           │   ├── open && kind=weapon → menuAlreadyOpen=true (skip walk)
│   │           │   └── otherwise           → vision-based keeper approach walk
│   │           ├── BuyAtShop × 4 chars (first call: menuAlreadyOpen=true)
│   │           └── ExitInterior + EquipWeapon × 4 chars
│   └── strategic LLM loop (Garland goal, etc.)
```

## Empirical trajectory (5 runs this continuation)

| Run | What it tested | Outcome |
|-----|----------------|---------|
| #1 | First arch fix (`ShopUiDetector` only) | DNS hiccup before boot reached advisor; inconclusive |
| #2 | Same code, network retry | Ktor EOFException — Gemini and Anthropic flaked at the same time |
| #3 | Same code, third try | **entered=true via vision_kind=weapon** (first ever); 12/12 BuyAtShop fail with `WrongClass` because B-spam misaligned menu |
| #4 | `menuAlreadyOpen=true` flag | Walked into ARMOR shop instead of WEAPON. detector accepted, 12/12 fail (wrong kind) |
| #5 | kind=="weapon" filter | **char1 bought Wooden Staff (5G)**; char2/3/4 WrongClass (class-naive slot mapping); EquipWeapon MenuStuck |

## Remaining work (non-architectural; surfaced by the unblock)

1. **Class-aware slot mapping in `runOutfitBootPhase` per-char buy loop.** The current `weaponSlotByChar = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3)` plus `(slot+1) % 4` retry cycle is a uniform default that ignores each char's class. Fighter/Knight, BlackBelt/Master, Thief/Ninja, Red/White/Black Mage have disjoint usable-weapon sets; the BUY list mixes class-restricted items, so a fixed mapping coincidentally fits at most one class per shop. Sketch fix: read `char{N}_class` from RAM (need to add register to `profiles/ff1.json`), maintain a per-class compatible-slot list, and try only those slots for that char. Without this, only one of four chars can succeed per run. Estimated 1–2 hours including test.

2. **`EquipWeapon` MenuStuck.** Run #5: `boot_equip: char1 slot=0 result=MenuStuck: 60 taps without equipped-flag transition for char=1 slot=0`. The skill drives the in-menu equip flow but the equipped-flag (high bit of `char{N}_weapon{slot}`) never flips. Most likely a navigation issue (cursor on wrong row when A is pressed, or one of the menu screens has changed slightly under the fanslated ROM). Needs a screenshot dump in EquipWeapon similar to `/tmp/spec5-boot-iter-*.png` to localise where the loop stalls. Estimated 1–3 hours.

3. **Strategic-loop budget pressure.** Run #5 hit `OutOfBudget` 4:18 in (maxSkillInvocations=120 cap). Boot phase finished cleanly at turn 43; the cap was burned by the post-boot strategic loop. Either raise the cap when running buy-then-equip integration tests, or have the boot phase return earlier when the orchestrated sequence is structurally complete (even if some chars failed) so the strategic loop can run longer.

4. **Fanslated-ROM dialog noise.** The current `roms/ff.nes` has fan-translated NPC dialog ("No Shit." text observed in run #4 iter 29 at smPlayer (10,11)). Not a code issue, but Gemini occasionally misreads such dialogs as shop UI when the actual menu hasn't opened. The kind-aware Done filter neutralises this — `classifyShopMenu` returns "unknown" for non-shop dialogs, so the open=false branch fires.

## What's uncommitted

Just `.claude/scheduled_tasks.lock` (local Claude scheduler). All session work landed in `18e58cd`, `7bd2a91`, `86faf9b`.

## Run command (next-session diagnostic re-run)

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
grep -E 'boot_walk_settle|boot_advisor\[|boot_advisor_done_verify|boot_advisor_wrong_shop|boot_advisor_summary|boot_post_enter|boot_keeper_approach|boot_purchase|boot_equip|boot_outfit_summary' "$LATEST/trace.jsonl"
```

Diagnostic screenshots accumulate in `/tmp/spec5-boot-iter-NN-midM-mfF-posPX_PY-smSX_SY-wWX_WY.png` plus `/tmp/spec5-postenter.png`.

## Lessons (carried forward — also in `~/.claude/projects/.../memory/`)

1. **Vision-primary detection beats RAM heuristics for FF1 UI overlays.** Run #4 confirmed: `classifyShopMenu` was the sufficient positive signal that worked in production; RAM gates only ruled out impossible regimes cheaply. Saved as `feedback_vision_primary.md`.
2. **FF1 NES shops are NPC dialog overlays in town overlay, not sub-maps.** `mapflags.bit0=1`, `mapId=0` is the canonical in-shop regime. Saved as `reference_ff1_shop_architecture.md` — now empirically end-to-end validated.
3. **Don't reset state you can consume.** Original instinct after `entered=true` was "B-spam to clean menu, then BuyAtShop from clean state". Better: read the menu state with vision, set `menuAlreadyOpen=true`, and consume the existing dialog. B-spam introduced its own off-by-one bug because FF1's B-button semantics differ across menu layers.
4. **Filter on the specific kind your skill needs.** `ShopUiDetector` accepts any shop kind by design (cheap); the caller must filter (`kind == "weapon"`). Run #4's armor-shop trap is the canonical example.
5. **Architectural fixes unblock pre-existing latent bugs.** Class-naive slot mapping and `EquipWeapon` MenuStuck were always wrong; they only became visible once BUY actually executed. Plan for the next layer of bugs before celebrating the fix.

## Carried-over principles

- **Autonomy:** agent gra grę; dev nie. Per `autonomy_principle.md`.
- **No-savestate persistence:** new specs nie używają savestate-hash-keyed flags ani FF1_SAVESTATE-gated e2e tests; persistence flows through `landmarkMemory`. Per `feedback_no_savestate.md`.
- **Locate-party-first vision prompts:** still in effect. Per `feedback_locate_party_first.md`.
- **Vision-primary UI detection:** new lesson, see #1 above. Per `feedback_vision_primary.md`.
