# knes-agent v2 — Design

**Date:** 2026-05-12
**Author:** Artur Skowroński (brainstorm with assistant)
**Status:** Approved design, ready for writing-plans
**Source motivation:** `docs/Gemini Plays Final Fantasy.pdf` — faithful implementation of the architecture from the talk, as a parallel agent runnable side-by-side with the existing `knes.agent.*`.

---

## 1. Goal

Build a second FF1 agent (`knes.agent.v2.*`) that faithfully implements the architecture described in *Gemini Plays Final Fantasy*: four cooperating roles (Cartographer / Advisor / Executor / Reviewer), a stuck-signal watchdog, a hierarchical decision-log save format, and a slim composable tool surface. v1 stays unchanged; we can flip between them at will for A/B comparison.

**Scope D (first full smoke target):** boot → warp to Coneria → buy 4× weapon → equip → exit town → grind cycle. Must support `--resume` from any kill point.

---

## 2. Layout & runnability

- **Module:** same `knes-agent` gradle module. New package `knes.agent.v2.*` alongside existing `knes.agent.*`.
- **Entry points:**
  - `:knes-agent:run` → `knes.agent.Main` (v1, unchanged)
  - `:knes-agent:runV2` → `knes.agent.v2.Main` (v2, new)
- **Shared with v1 (reuse):**
  - `knes-agent-tools` (EmulatorToolset, results)
  - `knes.agent.perception.*` (OverworldMap, LandmarkMemory, FogOfWar, ROM decoder, classifiers, vision navigators)
  - `knes.agent.pathfinding.*`
  - Proven macros: `BuyAtShop` (V5.45 vision-advisor), `EquipWeapon`, `RestAtInn`, `battleFightAll`
- **New, v2-only:** orchestration loop, 4 agents, watchdog, decision-log save format, tool surface.
- **Memory isolation:** every run writes to `~/.knes/runs/<YYYY-MM-DD-HHMM>-v2/`. Symlink `~/.knes/runs/latest-v2` → newest run. v1 writes to its existing paths — no collisions.
- **Resume:** `runV2 --resume <run-dir>` loads `campaign.json` + latest savestate checkpoint + replays decisions since checkpoint.

## 3. Models

| Role | Model | Why |
|---|---|---|
| Cartographer | Gemini 3.1 Pro | Strongest vision + reasoning, ARC-AGI-2 77.1% |
| Advisor | Gemini 3.1 Pro | PDF default, strongest planner |
| Executor | Claude Sonnet 4.6 (`claude-sonnet-4-6`) | Cheap per-turn, good tool-use |
| Reviewer | Claude Haiku 4.5 (`claude-haiku-4-5-20251001`) | Cheap audit pass |

## 4. Four agents — roles, triggers, cost share

```
T=0 (fresh campaign)
────────────────────
1. Cartographer (Gemini 3.1 Pro)  ──► overworld-map.json
   online vision exploration         warps.json
   ~5-10 min, ~$0.20                 landmarks.json

2. Advisor (Gemini 3.1 Pro)       ──► current_plan.json
   reads Memory + screenshot,        campaign.json (opens)
   emits numbered plan

T=1..N (campaign loop)
──────────────────────
3. Executor (Sonnet 4.6) per turn ──► decisions/turn-NNNN.json
   reads current_plan.json,          snapshots/turn-NNNN.png
   picks tool from {3 verbs +
   4 macros}, observes outcome

4. Watchdog (deterministic)       ──► stuck-signal
   phase-aware: 3/5/10 turns         wakes Advisor

Every 50 turns:
5. Reviewer (Haiku 4.5)           ──► review.jsonl
   audits Memory, removes stale,     cartographer-flags.json
   flags inconsistencies
```

**Trigger matrix:**

| Agent | When | Cost share (est.) |
|---|---|---|
| Cartographer | T=0 fresh; re-triggered if Reviewer flags ≥3 inconsistencies | ~10% |
| Advisor | T=0 (after Cartographer); stuck-signal; Reviewer flag-for-replan; phase boundary (Indoors↔Overworld); Battle end | ~2% |
| Executor | Every turn | ~85% |
| Reviewer | Every 50 turns (turn-counter modulo) | ~3% |

## 5. Tool surface (Executor sees)

PDF lesson: *fewer, composable*. Three new low-level verbs + four proven macros reused from v1.

| Tool | Purpose |
|---|---|
| `walkTo(x, y)` | Overworld or interior — auto-pick pathfinder by current phase |
| `interactAt(x, y)` | `walkTo` then tap A (NPC / chest / door) |
| `useMenu(path: String)` | Deterministic menu walker, e.g. `"main/equip/char1/weapon/0"` |
| `BuyAtShop(items, charSlots)` | v1 V5.45 vision-advisor reuse |
| `EquipWeapon(charSlot, weaponSlot)` | v1 reuse — **known issue:** MenuStuck bug; planned advisor-rewrite as follow-up |
| `RestAtInn(innMapId)` | v1 reuse |
| `battleFightAll()` | v1 reuse |

## 6. Cartographer scope

**Mode B from brainstorm — online vision exploration.**

Cartographer runs after emulator boot, before Advisor. Pushes the party through overworld around spawn using vision (Gemini 3.1 Pro) + frontier exploration. Builds:

- Full overworld 16×16 around spawn (fog merged into `overworld-map.json`)
- Coneria warp tile (into `warps.json`)
- 3 NPC landmarks in Coneria (innkeeper / weapon shop / armor shop) via DiscoverShop / DiscoverInn vision

**Budget cap:** 600s wall-clock + max 60 vision calls. If exceeded, fallback to static ROM-decoder map (overworld terrain only; no NPCs). Reviewer may flag for targeted re-pass later.

Vision prompts MUST enforce `locate-party → locate-target → derive-direction` (per existing `feedback_locate_party_first` rule).

## 7. Reviewer scope

**Mode D from brainstorm — remove stale + flag-for-Cartographer.**

Reads: entire Memory (landmarks/warps/blockages/decision-log).
Writes:
- `review.jsonl` (append-only audit log; every action has a reason text)
- Mutates `landmarks.json`, `warps.json`, `blockages.json` — **only `remove` operations**, never add/edit
- `cartographer-flags.json` — inconsistencies (e.g. `blockages(x,y) ≠ terrain(x,y)`) flagged for next Cartographer pass

**Safety:** every `remove` is appended to `review.jsonl` first (diff before mutation). `review.jsonl` is replay-rollback-able if Reviewer corrupts ground truth.

## 8. Watchdog (stuck-signal)

**Mode D from brainstorm — compound, phase-aware.**

```kotlin
val thresholds = mapOf(
    Phase.Battle to 3, Phase.MenuStuck to 3,
    Phase.Overworld to 5, Phase.Indoors to 5,
    Phase.CartographerExplore to 10,
)
```

**"No progress"** = (RAM-snapshot hash identical) AND (no skill `outcome == Ok`) for N consecutive turns.

Watched RAM fields for hash: `worldX, worldY, currentMapId, char1_hp..char4_hp, gold, menuState`.

**On stuck-signal:** wake Advisor with diagnose payload (`"Indoors stuck 5 turns, RAM static, last 3 outcomes: WrongClass, MenuStuck, MenuStuck"`).

**Phase whitelist for "RAM-static is OK":** `Dialog`, `BattleMessage`, `Cutscene`. Watchdog does not increment counter in these.

## 9. Memory layout (per-run directory)

```
~/.knes/runs/2026-05-12-1430-v2/
├── campaign.json              ← high-level state (Advisor reads on resume)
├── current_plan.json          ← active plan (Advisor writes, Executor reads)
├── decisions/
│   ├── turn-00001.json
│   └── ...                    ← per-turn append (Executor writes)
├── snapshots/
│   ├── turn-00001.png         ← PER ITERATION (Executor dump before LLM call)
│   ├── turn-00002.png
│   └── archive.tar.zst        ← Reviewer-rotated old snapshots if dir > 500MB
├── landmarks.json             ← Cartographer writes, Reviewer may remove
├── warps.json                 ← Cartographer writes, includes failedWarpTiles
├── blockages.json             ← Executor writes (walk failures), Reviewer prunes
├── overworld-map.json         ← Cartographer writes (fog + terrain)
├── interior-maps/<mapId>.json ← incremental per visited interior
├── review.jsonl               ← Reviewer append-only audit log
├── cartographer-flags.json    ← inconsistencies flagged for next pass
└── savestate-checkpoints/
    ├── T100.nss               ← knes-savestate every 100 turns
    └── T200.nss
```

**Per-iter screenshot rule (CRITICAL, per `feedback_per_iter_screenshots`):** Executor MUST dump `snapshots/turn-NNNN.png` **before** the LLM call — so the PNG matches what the model actually saw, not the post-tap outcome. Macros (`BuyAtShop` V5.45 etc.) already dump per-iter internally; reused as-is.

### Schemas (illustrative)

**`campaign.json`:**
```json
{
  "started_at": "2026-05-12T14:30:00Z",
  "scope": "coneria_buy_equip_grind",
  "milestones": [
    {"id": "boot",         "status": "done", "turns": [1, 47]},
    {"id": "warp_coneria", "status": "done", "turns": [48, 120]},
    {"id": "buy_weapons",  "status": "in_progress", "turns": [121, null], "plan_step": 3}
  ],
  "plans": [
    {"turn": 48, "by": "advisor", "summary": "walk to (15,22) and engage weapon shop", "snapshot": "snapshots/turn-00048.png"},
    {"turn": 156, "by": "advisor", "summary": "REPLAN: BuyAtShop returned WrongClass for slot 3", "snapshot": "snapshots/turn-00156.png", "reason": "stuck-signal"}
  ],
  "reviews": [
    {"turn": 50, "removed": ["blockages:(11,14)"], "flagged": []},
    {"turn": 100, "removed": [], "flagged": ["terrain≠blockage @ (12,18)"]}
  ],
  "last_turn": 187
}
```

**`decisions/turn-NNNN.json`:**
```json
{
  "turn": 187,
  "frame": 234567,
  "phase": "Indoors:shop",
  "ram": {"worldX": 0, "worldY": 0, "currentMapId": 5, "gold": 250, "menuState": 3},
  "snapshot": "snapshots/turn-00187.png",
  "executor": {
    "model": "claude-sonnet-4-6",
    "tool": "useMenu",
    "args": {"path": "shop/buy/item/0/char/1"},
    "reasoning_summary": "step 3 says buy item 0 for fighter; cursor confirmed at item list",
    "outcome": "ok",
    "ms": 220
  },
  "watchdog": {"stuck_counter": 0, "threshold": 5}
}
```

**`review.jsonl`:**
```jsonl
{"turn": 50, "kind": "remove_stale", "entry": "blockages:(15,21)", "reason": "last hit 47 turns ago, NPC moves"}
{"turn": 50, "kind": "remove_stale", "entry": "landmarks:shop_door_coneria_west", "reason": "never referenced in plans"}
{"turn": 100, "kind": "flag_for_cartographer", "entry": "blockages(12,18)≠terrain(12,18)", "reason": "consistency"}
```

## 10. Resume protocol

1. Load latest `savestate-checkpoints/T*.nss` into emulator.
2. Replay button events from `decisions/turn-*.json` since that checkpoint to bring emulator + Memory in sync with `last_turn`.
3. Advisor reads `campaign.json` → sees milestones / plan history / reviews → knows where it came from.
4. Resume campaign loop from `last_turn + 1`.

This addresses PDF pain point: *"when thrown in the middle of the game — Advisor was clueless"*.

## 11. Main loop (pseudocode)

```kotlin
suspend fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)        // --resume <dir> | --fresh
    val run = RunDirectory.open(cfg)      // ~/.knes/runs/<ts>-v2/
    val session = EmulatorSession.connect()
    val toolset = EmulatorToolset(session)

    val memory = V2Memory(run.path)
    val advisor      = AdvisorAgent(GeminiPro31, memory, toolset)
    val executor     = ExecutorAgent(Sonnet46, memory, toolset, toolSurface())
    val reviewer     = ReviewerAgent(Haiku45, memory)
    val cartographer = CartographerAgent(GeminiPro31, memory, toolset)
    val watchdog     = Watchdog(phaseAwareThresholds())

    // Phase 0: bootstrap
    if (cfg.fresh) {
        cartographer.exploreInitialOverworld(maxBudget = 600.seconds, maxVisionCalls = 60)
        advisor.plan(reason = "T0 fresh campaign")
    } else {
        resume(run, session)              // load savestate + replay decisions
        advisor.plan(reason = "resume context")
    }

    // Phase 1: campaign loop
    while (memory.campaign.scope != Done) {
        val turn = memory.nextTurn()
        val obs = observe(session, toolset)
        run.dumpSnapshot(turn, obs.screenshot)        // PER-ITER PNG (mandatory)
        val decision = executor.pickTool(obs, memory.currentPlan)
        val outcome = invoke(decision, toolset)
        memory.appendTurn(turn, obs, decision, outcome)

        watchdog.observe(turn, obs, outcome)
        if (watchdog.stuckSignal()) {
            advisor.plan(reason = "stuck: ${watchdog.diagnose()}")
            watchdog.reset()
        }

        if (turn % 50 == 0) {
            reviewer.audit(memory)
            if (memory.cartographerFlags.size >= 3) {
                cartographer.targetedRepass(memory.cartographerFlags)
                memory.cartographerFlags.clear()
            }
        }

        if (turn % 100 == 0) session.saveSavestate(run.checkpoint(turn))
    }
}
```

## 12. Testing strategy

PDF lesson: *"Unit Tests were nearly useless. Burned a lot of tokens for generating these tests."* — minimal automated tests; smoke runs are the real test surface.

**Only 3 minimal automated tests (just enough to code safely):**
1. **`Watchdog phase-aware threshold`** — stuck-counter logic, phase whitelist. Touched on every tuning iteration.
2. **`V2Memory atomic write + resume`** — load `campaign.json` after kill and verify milestones/last_turn restored. If this drifts, A/B vs v1 is meaningless.
3. **`MenuWalker path parser`** — `"main/equip/char1/weapon/0"` → button sequence. PDF + v1 evidence: menu-path typo = MenuStuck.

**Agents (Reviewer / Cartographer / Advisor / Executor): no tests.** Smoke runs against real ROM.

**Smoke plan (manual, per PR):**
1. **Smoke 0 — fresh boot.** `runV2 --fresh` from title. Success: `campaign.json.milestones[boot].status == "done"`.
2. **Smoke 1 — buy + equip.** Continuation or resume. Success: ≥3/4 weapons bought + equipped, party on overworld.
3. **Smoke 2 — grind cycle.** Success: ≥1 encounter + battle won + return to safe tile. Reviewer audit log non-empty.
4. **Smoke 3 — resume.** Kill mid-Smoke 2, `runV2 --resume <dir>`. Success: Advisor reads campaign, continues grind without re-planning from scratch.
5. **A/B vs v1.** Same ROM, fresh start, both to `buy_weapons.done`. Compare: turns-to-done, total cost USD, vision call count, % skill `outcome=Ok`.

## 13. Invariants (protected hard wins, per `feedback_protect_hard_wins`)

- `BuyAtShop` V5.45 reused verbatim — smoke 1 must match gold delta + equip flags from v1.
- `EquipWeapon` MenuStuck bug — known, inherited from v1. **Planned: advisor-rewrite as follow-up phase** (same pattern as BuyAtShop V5.44→V5.45). Not a v2 launch blocker.
- Vision-LLM nav: `locate-party → locate-target → derive-direction` enforced in Cartographer prompt (per `feedback_locate_party_first`).
- No savestate-hash-keyed flags (per `feedback_no_savestate`) — landmarkMemory-only.

## 14. Risks

| Risk | Mitigation |
|---|---|
| Cartographer wide exploration costs >$1/run | Budget cap 600s + max 60 vision calls; fallback to static ROM-decoder map; Reviewer flags for targeted re-pass |
| Reviewer corrupts Memory (Haiku hallucinates remove) | `review.jsonl` diff before mutation; every `remove` has reason; replay-from-jsonl rollback |
| Watchdog false-positive in legit-static phases (dialog wait) | Phase whitelist (Dialog/BattleMessage/Cutscene); watchdog does not tick |
| 3 verbs too thin — Executor mis-composes `useMenu` paths | Smoke 1 detects. Fallback: add `useMenuEquip(char, weapon)` convenience macro |
| Resume cannot reconstruct state (savestate format drift) | Stabilize savestate v1 (per `plans/2026-05-10-knes-save-format.md`) before v2 launch |
| Per-iter PNG dump bloats disk | Reviewer rotates `snapshots/` > 500MB into `archive.tar.zst` |

## 15. Out of scope (explicit non-goals)

- Replacing v1 — v1 stays untouched, runnable.
- Rewriting `BuyAtShop` / `EquipWeapon` / `RestAtInn` — reused as-is from v1.
- Generic-game framework — v2 is FF1-only, like v1.
- Distributed / multi-process — single JVM, single emulator.
- E2E test suite — smoke runs are the test surface (per PDF lesson).

## 16. Next step

Invoke `superpowers:writing-plans` to produce the implementation plan.
