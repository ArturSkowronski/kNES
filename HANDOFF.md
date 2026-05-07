# FF1 Koog Agent — Handoff (Spec 2 buy & equip session, 2026-05-07)

**Branch HEAD:** `3d07e69` on `ff1-buy-at-shop` (pushed to origin, **no PR open yet**).

Cut from `ff1-grind-strategy` (PR #116, Spec 1 still open). Spec 2 PR is user-driven — not opened by agent.

**Required env:** `ANTHROPIC_API_KEY` for Phase 2 advisor; `GEMINI_API_KEY` for shop-menu vision (`HaikuConsult.classifyShopMenu` uses Gemini).

## TL;DR

Branch ships full **Spec 2 buy-and-equip boot phase**: agent walks to Coneria on session start, vision-classifies the weapon shop, buys one weapon per char, equips them, persists savestate-keyed flag so subsequent sessions skip. **24 new tests green, 0 regressions.**

Implementation is complete on the local branch. **Validation runs against real ROM are pending — user-driven** per the autonomy principle (agent plays the game, dev does not).

## What's in this branch (15 commits)

```
3d07e69 refactor(session): inject OutfitState — eliminate e2e test pollution risk
4f48898 test(e2e): OutfitBootPhaseE2ETest — env-gated real-ROM verification of boot phase
1984947 fix(bootphase): pass coneriaEntry through Result; document discover retry semantics
ca6d248 feat(session): outfit boot phase — discover shop, buy + equip weapons before grind
daf4338 fix(equipWeapon): unify recovery B-tap count + add cursor-loop coverage test
615df52 feat(skill): EquipWeapon — field-menu nav, equip-flag validation, idempotent
4909744 fix(buyAtShop): weapon-only guard + document magic numbers and B-tap counts
3f2cdf1 feat(skill): BuyAtShop — one purchase per invocation, gold + inventory validation
b9033b1 fix(discoverShop): surface vision exceptions in ClassifyFailed; align KDoc with B-mash cleanup
e3a5bce feat(skill): DiscoverShop — vision-classify shop BUY menu, persist landmark
d1b981f feat(vision): classifyShopMenu — Gemini reads FF1 shop BUY screen
f87352e feat(runtime): OutfitState persistence — savestate-hash-keyed boot skip flag
5a76882 feat(strategy): add weapon-slot helpers to StrategyContext
344f4dc feat(profile): add char weapon-slot RAM addresses to FF1 profile
4745d75 docs(notes): document FF1 weapon RAM addresses from Disch disasm
```

Plus 2 doc commits on `ff1-grind-strategy` (parent branch):

```
05e84e9 docs(plan): FF1 buy & equip implementation plan (Spec 2)
72866b0 docs(spec): FF1 buy & equip design (Spec 2 of 2)
```

## Components

- **`OutfitState`** — savestate-hash-keyed JSON at `~/.knes/ff1-outfit-state.json`. AtomicJsonWriter, lenient load. Injectable via `AgentSession` ctor for test isolation.
- **`StrategyContext` weapon helpers** — `weaponSlot`, `weaponId`, `isEquipped`, `anyWeaponEquipped`. Reads new RAM keys.
- **16 weapon RAM addresses** in `ff1.json` — `char{1..4}_weapon{0..3}` at `0x6118 + 0x40*c` per Disch disasm. High bit = equipped flag, low 7 = weapon ID.
- **`HaikuConsult.classifyShopMenu`** — `ShopClassification(kind, items, costUsd)`. Gemini implementation reads BUY-menu screen. Anthropic stub returns "unknown".
- **`DiscoverShop` skill** — opens BUY menu, screenshot → Gemini classify → persist `NPC_SHOPKEEPER` landmark with `note="kind=...; items=name:price,..."`. Self-closes via B-mash. Returns Classified / ClassifyFailed / NotInBuilding.
- **`BuyAtShop` skill** — one purchase per invocation. Args: itemSlot, forCharSlot, expectedKeeperKind. Sync InsufficientGold pre-check. Watch loop: gold-drop AND inventory-delta = Bought; 5 unchanged frames = WrongClass; 30-frame cap = DismissCapExhausted. Weapon-only guard.
- **`EquipWeapon` skill** — opens FF1 field menu (B), navigates EQUIP → char → weapon slot, watches RAM for high-bit transition. Idempotent (AlreadyEquipped sync). 60-tap cap, 5-tap recovery B-mash on both success and timeout.
- **`OutfitBootPhase`** — orchestrator with entry guards (already-bought flag, RAM-all-equipped, no town entry). Returns coneriaEntry through Result for composer reuse.
- **`AgentSession.runOutfitBootPhase()`** — invoked at top of `run()` before strategic loop. Walks to Coneria → discoverWeaponShop probe (vision-only N-retry; coord targeting deferred) → per-char buy loop with WrongClass mini-retry (3 attempts, slot rotation) → exit interior → per-char equip loop on overworld → persist OutfitState → log `boot_outfit_summary`. Best-effort: never bails session.
- **5 new optional ctor params** on `AgentSession`: `outfitVision`, `outfitNavigator`, `outfitViewportSource`, `outfitMapSession`, `outfitSavestatePath`, `outfitState`. All nullable; missing → fallthrough with `dependencies_unavailable` log.
- **E2E test `OutfitBootPhaseE2ETest`** — env-gated on `FF1_ROM_PATH` + `FF1_SAVESTATE`. Stubs Gemini; pre-seeds Coneria TOWN_ENTRY. Soft assertions only when shop discovery succeeds. CI without env vars: SKIPPED cleanly.

## Tests
- 23 new unit + 1 env-gated e2e — all green.
- 35 Spec 1 tests still green; 0 new regressions.
- 3 baseline failures unchanged (Coneria8VisualDiffTest, ConeriaTownEmpiricalDiscoveryTest, ExploreOverworldFrontierTest — pre-existing).

## Validation status

**Pending — user-driven.** Implementation has not been run against real ROM yet. Per `autonomy_principle` memory, agent plays the game, dev does not — but real-ROM execution requires interactive ANTHROPIC_API_KEY + GEMINI_API_KEY which the dev triggers manually.

**Run command:**
```bash
rm -f ~/.knes/ff1-outfit-state.json ~/.knes/ff1-landmarks.json
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes --wall-clock-cap-seconds=420 --cost-cap-usd=2.0 --max-skill-invocations=80"
```

3× sessions; capture from each trace:
- `boot_outfit_summary` line (weaponsBought, weaponsEquipped, totalGoldSpent)
- per-char `boot_purchase` / `boot_equip` outcomes
- death-restart count (target ≤5; PR #116 baseline was 32)
- whether `min_level` reaches 3 within budget

## Known risks to flag in eventual Spec 2 PR

1. **`WalkInteriorVision` lacks coord targeting** — `discoverWeaponShop` falls back to N-retry vision-only nav. If validation shows poor probe success, follow-up spec adds coord-targeted interior nav.
2. **Default `weaponSlotByChar = {1→0, 2→1, 3→2, 4→3}`** is placeholder. WrongClass mini-retry (3 attempts/char with slot rotation) handles miscalibration; if all 4 chars hit WrongClass consistently, mapping needs adjustment based on real Coneria shop ordering.
3. **`AgentSession` outfit-deps signature growth** (6 nullable params) — collapse into `OutfitDeps` data class as future cleanup if a 7th dep lands.
4. **Tap counts in BuyAtShop / EquipWeapon are unverified vs real ROM.** Spec assumed standard FF1 NES menu nav; first real-ROM run may surface adjustments needed.

## Next session entry point

User runs validation + opens PR. After Spec 2 lands:
- If validation shows `weaponsEquipped ≥ 1` AND death count drops to ≤5 → Spec 2 viable. Merge order: PR #116 → Spec 2 PR.
- If validation shows poor shop-discovery success → follow-up spec adds coord-targeted `WalkInteriorTo` interior skill.
- Then re-run Spec 1 validation with weapons-equipped party to confirm grind→heal→bridge full flow.

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Branch: `ff1-buy-at-shop` HEAD `3d07e69` (pushed to origin)
- Parent (PR #116): `ff1-grind-strategy`
- Spec 2 design: `docs/superpowers/specs/2026-05-06-ff1-buy-at-shop-design.md`
- Spec 2 plan: `docs/superpowers/plans/2026-05-06-ff1-buy-at-shop.md`
- RAM notes: `docs/superpowers/notes/2026-05-06-ff1-weapon-ram.md`
- E2E test: `knes-agent/src/test/kotlin/knes/agent/runtime/OutfitBootPhaseE2ETest.kt`
