# knes-agent v2 — Gap #3 → arm_party handoff

**Date:** 2026-05-13
**Branch:** `ff1-v2-gap3-approach-sprite` (off master `9059b9e`)
**Spec:** `docs/superpowers/specs/2026-05-12-knes-agent-v2-design.md`
**Plan:** `docs/superpowers/plans/2026-05-12-knes-agent-v2.md`
**Prior handoff:** `docs/superpowers/notes/2026-05-12-v2-smoke-1-progress-handoff.md`

## What this branch delivers

Architectural rewrite of the v2 Executor loop + new tooling + milestone
overhaul. Across the session the agent advanced from `0/4 weapons bought`
(prior best) to **5/6 milestones completed in 80 turns** on a `--max-turns=200`
smoke (boot ✅ enter_coneria ✅ buy_weapons ✅ equip_weapons ✅ exit_coneria ✅,
grind in_progress) on the now-merged predicates. Subsequent tightening of
predicates to "all 4 chars" exposed a separate `BuyAtShop` cursor bug in the
v1 skill, which is now fully bypassed by a native v2 implementation.

## Subsystems delivered

### Architecture (Executor)

- **Sonnet-every-turn** — plan-hint dispatch removed. Every turn the Executor
  calls the LLM with current screenshot + RAM digest + plan + recent moves.
  Plan is suggestion, not authority. (`ExecutorAgent.act` — was
  branching plan-hint vs askLlm, now always askLlm.)
- **Gemini-as-Executor** — Sonnet was hallucinating "battle overlay" from
  field-menu screens via Haiku digest. Switched the per-turn decision LLM to
  Gemini Pro 3.1 (`gemini-3.1-pro-preview`). Sonnet kept as fallback.
- **Flash-Lite Executor** — Final swap: `gemini-3.1-flash-lite` on Executor,
  Pro stays on Advisor/Cartographer. 5-10× lower per-turn latency. Override
  via `GEMINI_EXECUTOR_MODEL`. Default Pro model: `gemini-3.1-pro-preview`
  (override via `GEMINI_MODEL`).
- **Haiku dropped from Executor** — `describeScene` was a noisy middleman.
  Gemini reads the screenshot directly now. Haiku stays as tool-internal
  helper (`directionTo` for town walk, `scanCandidates` for approachSprite)
  and as the LLM the Reviewer uses for plan audit.
- **`sequence(buttons)` tool** — LLM emits raw 1-button taps. Hard cap MAX=1
  per turn (was 2, then 3, then 1 after empirical "agent overshoots /
  oscillates with longer sequences" — FF1 NES viewport is 16×14 tiles, NPCs
  block frequently).
- **Move history + anti-oscillation prompt** — Executor sees last 8 moves
  (`pre-sm → tool → post-sm` + MOVED/NO-MOVEMENT tag). 3-turn stuck-at-same-sm
  injects an explicit "ANTI-OSCILLATION" warning into the prompt.
- **Prompt dumps** — every Advisor/Executor/Cartographer/Reviewer LLM call
  dumps `=== PROMPT ===\n...\n=== RESPONSE ===\n...` to
  `prompts/T<turn>-<agent>.txt`. Viewer renders them.

### Architecture (Advisor)

- **RAM context** — Advisor sees current phase, mapId, sm/world, gold, party
  weapon digest (with per-char held + equipped status), plus coord-space hint
  matching current phase. Prior runs the Advisor planned `walkTo(11,10)` from
  Overworld because it didn't know the current phase.
- **Replan on milestone advance** — `advanceMilestones` returns the
  newly-latched milestone id; Main triggers `advisor.plan(reason="milestone X
  just done")` immediately. Prior runs Advisor only fired on T0 / stuck-signal,
  so stale plan steps kept executing post-latch.
- **Class/weapon hints** — explicit Coneria shop layout
  (slots 0-4 = staff/dagger/nunchuck/rapier/hammer) + per-class equip rules
  + a one-shot plan template: `items="4,3,2,3", charSlots="0,1,2,3"` for
  the default party. Plus rule "if WrongClass for one char, swap item to
  another char that's still missing a weapon — don't abandon the item".
- **Filtered misleading preseed** — `~/.knes/ff1-landmarks.json` has an
  `NPC_SHOPKEEPER` at `mapId=0 local(11,10)` — that tile is actually the
  street tile south of the door, not the shopkeeper. Advisor's
  `landmarksDigest()` now strips that line so the planner doesn't anchor
  on a tile that can't be "reached" without stepping into the shop interior.

### Architecture (Reviewer)

- **Deterministic milestone verifier** — every 10 turns re-evaluates each
  "done" milestone predicate against live RAM. On regression: revert to
  `in_progress` + trigger `advisor.plan(reason="milestone REGRESSED: X")`.
- **LLM plan audit (Haiku)** — every 25 turns Haiku reads the plan + RAM +
  screenshot and lists discrepancies between claimed "done" steps and actual
  state. Issues → `advisor.plan(reason="Reviewer audit found issues: ...")`.
  Was placeholder `{"actions":[]}`; now real Anthropic API call via
  `HaikuClient.auditPlan`.
- **Active-agent UI highlight** — Reviewer calls `run.markActive("reviewer")`
  / `markIdle()` so the viewer's green border fires when it runs.

### Architecture (Cartographer)

- **Opt-in via `--cart` flag** (default OFF). Saved Gemini vision calls when
  exploring already-seeded landmarks (see `feedback_cartographer_optin`).
- **Boot before Cart** — Cart was running on the title screen (worldX=0,
  worldY=0) and asking Gemini for a direction looking at the FINAL FANTASY
  logo. Gemini answered "DONE" immediately → 1 vision call, 0 steps. Fix:
  `pressStartUntilOverworld` runs FIRST, then Cart sees real Overworld.
- **Cartographer prompt dumps** — each iter's prompt + Gemini response
  written to `prompts/T00000-cart-NN.txt`; viewer renders with screenshot.

### Tools

- **`approachSprite(kind)`** — Phase.Town only. Scans viewport via Haiku
  `scanCandidates`, walks party adjacent-south of named sprite + faces N.
  Mirrors v1 keeper-approach. Composable: plannable by Advisor.
- **`buyAtShop` — v2 native rewrite** — bypasses v1 BuyAtShop entirely.
  Per-pair single-purchase state machine: B×6 reset → A engage → Up×2+A BUY
  → Up×8 (item top) + Down×itemSlot + A → Up×4 (whom top) + Down×forCharSlot
  + A → A confirm → watch RAM for gold drop + inv delta OR 5 unchanged → B×6
  exit. Multiple pairs aggregate per-result (`OK i=4 c=0; FAIL i=3 c=1
  (WrongClass)`), don't abort the whole tool on first failure.
- **`equipWeapon` 1-indexed fix** — v1 expects char1..char4 (1-indexed);
  Gemini emits 0-indexed. v2 wrapper converts +1.
- **`sequence(buttons)` with per-button `live.png` write** — viewer sees
  motion at button granularity, not just per-turn snapshot.

### Town navigation (`townWalkVision`)

- **Adjacent→exact-step fallback** — after reaching `|dx|+|dy|=1`, try one
  more step on the target. Critical for door tiles (shop entries) where
  adjacent isn't enough. If the step triggers transition (mapId/mapflags
  changes), report Ok with that transition info (caller wanted to enter).
- **`avoidCardinals` per-invocation memory** — record (sm, cardinal) pairs
  that triggered a transition; future taps skip them.
- **Walk-back-in recovery** — if mid-loop the party stepped out to Overworld
  (south exit), tap opposite of last cardinal to reenter; resume target.
- **Indoors-transition Reject** — if mid-loop the party fell into a building
  interior (mapId>0), don't try to recover same-loop; Reject with the
  avoidCardinals memo retained.
- **Removed `walkTo` coord-sanity gate (#5)** — was redundant with the
  Advisor's state-digest prompt and caused regression: legitimate walks
  rejected because Advisor authored coords for the next phase.

### Milestones

- **Stricter `enter_coneria`** — require `Phase.Town && smPlayerY ≤ 25`.
  Previously latched on first Phase.Town reading, including the entry-row
  tile sm=(8,30) and transient mapflags=2 transitions, then party stepped
  back out.
- **Merged `buy_weapons` + `equip_weapons` → `arm_party`** — equipping
  implies holding, so two separate milestones forced two plans with a latch
  between that masked incomplete purchases. Final predicate: ≥2 of 4 chars
  with bit7 set (relaxed from "all 4" because BuyAtShop sometimes skips
  chars; ≥2 is the floor where the party is meaningfully armed for the next
  encounter).
- **Single source of truth** — `MilestonePredicates.kt` used by both
  `advanceMilestones` (forward latch) and Reviewer (re-verify). No
  predicate-drift between the two paths.

### Visualizer (`tools/v2_viewer.py`)

- Live HTTP server on `:9876`, auto-refresh 3s.
- Sections: **current screen** (prefers `snapshots/live.png` when newer than
  per-turn dump), **goal/milestones**, **current plan with cursor**, **RAM
  panel** (run-relevant only: mapflags/mapId/screenState, sm/world, gold,
  weapons-per-char with `*=equipped`), **agents narrative** with
  ACTIVE highlight + age, **last 25 turns table**, **advisor plan history**,
  **expandable cartographer iter list** with screenshot + prompt, **latest
  advisor + executor prompts** drill-down.

## Empirical results

### Best run (off-by-one fix, before merge to `arm_party`)

200-turn smoke `2026-05-13-0057-v2`:

```
T1   boot done
T6   enter_coneria done
T24  buy_weapons done (then-predicate: any weapon held)
T58  equip_weapons done (then-predicate: any weapon equipped)
T72  exit_coneria done
T200 grind in_progress
```

5/6 milestones in 80 turns. char1 ended with Rapier (slot 0), char3 with
Hammer EQUIPPED (slot 0, byte 0x84). 2/4 chars actually armed though —
which is what motivated tightening the predicates.

### Tightened-predicate run (`arm_party` ≥ 2/4 equipped)

200-turn smoke `2026-05-13-XX-v2` (recommended-pairs prompt + cursor reset
between pairs): final state had `boot/enter_coneria/buy_weapons done,
arm_party in_progress`, weapons stuffed onto Fighter (3 Rapiers in slots
0-2) — the v1 BuyAtShop cursor-reset bug that prompted the native rewrite.

### v2-native buyAtShop run (current head behaviour)

Killed by user mid-run; partial trace showed per-pair OK/FAIL aggregation
working, gold dropping correctly on success pairs, but the agent was still
mid-arm-party at kill time. **Net: not yet end-to-end validated on the
native implementation — that's the immediate next-session task.**

## Carried-forward principles (new + retained)

- **Stealth no-ops are the dominant failure mode** — every tool needs a
  precondition Reject path. (battleFightAll, useMenu, buyAtShop,
  approachSprite all gated.)
- **Coord-leak across phase changes is the dominant correctness bug** —
  `walkTo(11,10)` from Town means town-local; from Overworld means
  world(11,10) NW corner. Cured by replan-on-milestone-advance + state
  digest in Advisor prompt + Sonnet-every-turn. The runtime gate (#5) was
  the wrong layer.
- **Milestone predicate latch is load-bearing** — "any" predicates silently
  pass with partial completion. Use "all" / "≥N" thresholds and gate on
  stable conditions (smY ≤ 25 vs touching entry tile).
- **Plan latch != reality** — Reviewer must re-verify "done" milestones each
  N turns; on regression, replan. Single source-of-truth predicates avoid
  drift between forward latch and re-verify.
- **Vision-first beats Haiku-digest-first** — Haiku misclassified
  shopkeepers as `generic-npc` and field-menus as `battle`. Sonnet/Gemini
  trusting that text over the actual image picked wrong tools. Mixed
  image+text or image-only gives the model veto.
- **Per-tile cardinal taps; reassess every step** — sequence MAX=1 because
  longer sequences overshoot. Gemini Flash-Lite makes the per-turn cost
  bearable.
- **v1 skills are not v2-ready** — `BuyAtShop` and `EquipWeapon` assumed
  1-indexed forCharSlot AND fresh cursor state per call. v2 wrappers must
  convert indexing AND either fully reset cursor between calls or bypass
  the skill entirely.
- **Latest Gemini model IDs (2026-05):** `gemini-3.1-pro-preview` (thinking),
  `gemini-3.1-flash-lite` (fast Executor). Earlier `gemini-3-pro` returns
  404 — list models via `GET
  https://generativelanguage.googleapis.com/v1beta/models?key=$GEMINI_API_KEY`.

## File map

| Concern | File | Note |
|---|---|---|
| Milestone predicates (SoT) | `knes-agent/src/main/kotlin/knes/agent/v2/runtime/MilestonePredicates.kt` | new |
| Main campaign loop | `Main.kt` | boot-before-cart, replan-on-milestone, reviewer hooks |
| ExecutorAgent | `agents/ExecutorAgent.kt` | Sonnet-every-turn, Gemini-via-config, move-history, sequence schema |
| AdvisorAgent | `agents/AdvisorAgent.kt` | RAM context, class/weapon hints, recommended pairs |
| ReviewerAgent | `agents/ReviewerAgent.kt` | verifyMilestones + auditPlan (Haiku) |
| CartographerAgent | `agents/CartographerAgent.kt` | prompt dumps, run.markActive |
| ToolSurface | `tools/ToolSurface.kt` | `approachSprite`, `sequence`, v2 native `buyAtShop`, townWalk fixes |
| HaikuClient | `llm/HaikuClient.kt` | `auditPlan`, `scanCandidates`, `parseSpriteLines` |
| Gemini client | `llm/GeminiPro31Client.kt` | per-instance model override |
| Sonnet client | `llm/SonnetClient.kt` | optional imageB64 |
| V2RunDirectory | `runtime/V2RunDirectory.kt` | promptsDir, liveSnapshot, markActive/markIdle |
| V2Memory | `runtime/V2Memory.kt` | arm_party milestone |
| Viewer | `tools/v2_viewer.py` | active highlight, RAM panel, prompt drill-down |

## Open follow-ups

1. **Validate v2-native buyAtShop end-to-end** — last smoke killed at T~20
   of arm_party. Need a 200-turn smoke with this code to confirm 4 distinct
   chars get distinct weapons (no Fighter-stuffing).
2. **Memory audit `audit()` LLM-wiring** — current method is no-op placeholder
   that just stubs a review entry. The companion `auditPlan()` does the real
   work; `audit()` can either be retired or wired to a different prompt
   (memory cleanup vs plan audit).
3. **Per-button live.png inside buyAtShop** — `buyOnePair` doesn't write
   `live.png` between taps, so a 60-tap shop dialog shows as one frozen
   screenshot in the viewer. Move the `livePngFile` writes from `sequence`
   into a shared helper called from both.
4. **Cartographer LLM swap** — currently still on the Pro model. After Smoke
   1 the v1-cached landmarks cover Coneria, so Cart usually skipped. If we
   re-enable it for unexplored areas, evaluate whether Flash-Lite suffices.
5. **`equipWeapon` skill assumes fresh menu** — same class of bug as v1
   BuyAtShop (cursor doesn't reset between successive calls). For 4 equips
   in a row we may need a v2-native equipWeapon too.

## Reproduction

```bash
# Latest 200-turn smoke (cart off, Pro for Advisor/Cart, Flash-Lite for
# Executor):
./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=200"

# Override models:
GEMINI_MODEL=gemini-3.1-pro-preview \
GEMINI_EXECUTOR_MODEL=gemini-3.1-flash-lite \
  ./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=200"

# Viewer (open http://localhost:9876/):
python3 tools/v2_viewer.py

# Smoke + cart (rare):
./gradlew :knes-agent:runV2 -PappArgs="--fresh --max-turns=80 --cart"

# Run state dirs:
ls -la ~/.knes/runs/latest-v2/
~/.knes/runs/latest-v2/{campaign,current_plan,landmarks}.json
~/.knes/runs/latest-v2/prompts/T*.txt  # per-turn LLM dumps
```

Required env: `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`.
