# FF1 Koog Agent — Handoff (Spec 1 grind & heal cycle session, 2026-05-06 evening)

**Branch HEAD:** `a5c76d0` on `ff1-grind-strategy`. **PR #116 open** against `master`.

Cut from `ff1-phase2-garland-attempt-hardening` (PR #115 still open separately for prior session work).

**Required env:** `ANTHROPIC_API_KEY` (Phase 2 vision hardcoded to Anthropic in `Main.kt`).

## TL;DR

PR #116 ships full **strategy decision infrastructure** for Phase 2 agent: GRIND/REST/BRIDGE decision gate, GrindLoop / RestAtInn / DiscoverInn skills, advisor consult with sanity guards, plan-injection for REST cycle, autonomous inn discovery with landmark persistence. **35 new tests, all green, 0 regressions.**

Validation runs (4 attempts) revealed that **Spec 2 (`buyAtShop`) is a hard prerequisite** before grind strategy is viable end-to-end. Party L0 with starting weapons dies in 3-4 round IMP battles → death-restart loop → progress lost. Net consensus is "buy weapons FIRST, then grind" — Spec 2 was deferred speculatively in original planning, must ship before next validation.

## What's in PR #116 (chronological)

| Commit | Subject |
|---|---|
| 12c544d | docs(spec): FF1 grind & heal cycle design (Spec 1 of 2) |
| 53ee356 | docs(plan): FF1 grind & heal cycle implementation plan |
| 0b74fee | feat(strategy): StrategicDecision enum + token parser |
| d40487b | feat(strategy): RecentDecisionsBuffer with anti-thrash predicate |
| da31cb5 | feat(strategy): StrategyContext RAM helpers + summarize() |
| 1367579 | feat(skill): GrindLoop — N-S corridor walk near spawn |
| 8e93126 | fix(grindloop): correct framesElapsed delta + add 0x63 test |
| 61023c2 | feat(skill): RestAtInn — deterministic Coneria inn heal |
| 2e9083b | fix(restatinn): document taps>=4 heuristic + test already-full branch |
| e37e81d | feat(advisor): StrategyAdvice — prompt builder + sanity guards |
| 72b8773 | fix(strategy-advice): document L0/L1 BRIDGE override threshold |
| 7e6a269 | docs(spec+plan): autonomous inn discovery (replaces manual probe) |
| 5f6aa81 | feat(skill): DiscoverInn — autonomous inn discovery + persist landmark |
| 142637f | fix(discoverinn): correct interior RAM keys (smPlayerX/Y) + round-trip |
| aa733c8 | feat(session): strategic decision point + grindMode (V5.35) — MVP |
| 2a606fd | fix(session): pass grind anchor + correct trace phase |
| 664d6c7 | test(e2e): GrindAndHealCycleE2ETest with stub advisor |
| ed307f1 | fix(e2e-test): override plan() in stub + assert tickCount |
| 5109f40 | feat(session): REST heal cycle via executor plan-injection (V5.36) |
| 6b66ad9 | fix(session): capture grind anchor from party position dynamically |
| 09ba8ab | fix(grindloop-args): widen corridor 3->6, max steps 6->12 |
| a5c76d0 | feat(strategy): adaptive grind anchor — shift on consecutive non-progress |

## Validation iteration table

| Iter | Outcome | Strategy ticks | Battles | Death restarts | Diagnosis → Fix |
|------|---------|----------------|---------|------------------|-----------------|
| v1 | BuildFail | 0 | 0 | 0 | Relative ROM path → use absolute via `$PWD` |
| v2 | OutOfBudget | 50+ | 0 | 0 | Hardcoded anchor (157,158); spawn (146,158) → WanderedOff each step → drift into town. **Fixed:** dynamic anchor capture (`6b66ad9`) |
| v3 | OutOfBudget | 6 | 0 | 0 | Spawn area (146, 157-159) is no-encounter zone. Wider corridor reaches town entry but still no encounters. **Fixed:** adaptive anchor shift after 3 non-progress (`a5c76d0`) |
| v4 | OutOfBudget | 2/life | many | **32** | Adaptive shift → encounter at (152,151), party briefly L1, then dies in IMP encounter → restart cycle. Death loop dominates wallclock. **Spec 2 blocking.** |

## Critical insight from validation

**Death loop is the dominant blocker, not strategic infrastructure.**

Without weapon upgrades:
- Party L0 IMP encounter takes 3-4 rounds
- Multiple IMPs hit per round → 30 HP party can die in 2 rounds
- 32 deaths in 7-min run = ~13s per death cycle, dominates wallclock

With Coneria weapons (400 GP starting gold = enough for 4× starter weapons):
- Each class one-shots IMP/Goblin per net consensus
- 1-round battles → minimal HP loss → grind viable

User feedback (verbatim): *"oddal się od conerii, ale nie jakoś daleko - dodatkowo miałeś kupić broń"* — IMPs are close to town (not at bridge), and weapons MUST be bought.

## Known existing bugs (NOT in scope of this PR)

1. **Town overlay exit failures** — `exitInterior` 13% step success on towns (V5.29 docs); `exploreInteriorFrontier` often fails. Once warped into Coneria town at (146,152), high probability of getting stuck.
2. **Death-restart not detected as PartyDefeated** — `pressStartUntilOverworld` called from existing flow without `Outcome.PartyDefeated` returning. Agent silently restarts and continues.
3. **`PressStartUntilOverworld.totalFrames` accumulation** — same bug as fixed in `GrindLoop` (`8e93126`). Not fixed here.

## Next session entry point

**Highest-leverage next step: implement Spec 2 (`buyAtShop` skill).**

Sub-tasks:
- Walk to Coneria entry (TOWN_ENTRY landmark from Phase 1 explorer if available; else fallback)
- Enter town, navigate to weapon shop (vision-driven via `WalkInteriorVision`)
- Shop dialog: deterministic menu interaction (BUY → WEAPON → select per class → confirm)
- Validate: gold drops, item RAM slot populated
- Persist `NPC_SHOPKEEPER` landmark (already in `LandmarkKind` enum) on first discovery for cross-run reuse

Secondary:
- Reduce adaptive anchor shift offset from `(+2 E, -4 N)` to `(+3 E, -1 N)` — lateral move per user feedback ("oddal się od conerii, ale nie jakoś daleko").
- Validate cross-run landmark persistence after Spec 2 ships.

After Spec 2 works:
- Wire REST cycle to actually walk-to-inn (currently plan-injection logs cache hit/miss only)
- Validate full grind→heal→bridge flow

## Run command

```bash
./gradlew :knes-agent:run --args="--rom=$PWD/roms/ff.nes --wall-clock-cap-seconds=420 --cost-cap-usd=2.0 --max-skill-invocations=80"
```

## Files of note

- Spec: `docs/superpowers/specs/2026-05-06-ff1-grind-and-heal-cycle-design.md`
- Plan: `docs/superpowers/plans/2026-05-06-ff1-grind-and-heal-cycle.md`
- Strategy types: `knes-agent/src/main/kotlin/knes/agent/runtime/{StrategicDecision,RecentDecisionsBuffer,StrategyContext}.kt`
- Skills: `knes-agent/src/main/kotlin/knes/agent/skills/{GrindLoop,RestAtInn,DiscoverInn}.kt`
- Advisor: `knes-agent/src/main/kotlin/knes/agent/advisor/StrategyAdvice.kt` + `AdvisorAgent.consultStrategy`
- Session integration: `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` (V5.35 / V5.36 / V5.36.x sections)
- E2E test: `knes-agent/src/test/kotlin/knes/agent/runtime/GrindAndHealCycleE2ETest.kt`
