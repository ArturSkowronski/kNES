# FF1 Koog Agent — Handoff (Spec 5 — exit-from-Coneria BLOCKED, 2026-05-09 cont 3)

**Master HEAD:** `361e88e` (merge of PR #122).
**Branch:** `ff1-buy-and-equip-coneria` (still active for follow-up work; this cont session adds 4 working pieces + 1 known-broken fallback, **uncommitted** at handoff time).
**Tests:** 348 unit + 3 in `SavestateRoundtripDebug`. Compiles clean.

## TL;DR — 2026-05-09 cont 3 progress

**Goal:** post-buy → exit Coneria town → walk to grass → first random encounter.
**Result:** Buy still works (4/4 in 47–57 advisor iters across 3 reruns). **Exit fails** — agent gets stuck in Coneria town courtyard at smPlayer y=14. Strategic loop fires `GRIND` but `worldX/Y` doesn't change inside town overlay → `UnknownMapTrap` detector kills the run after 3 obs.

**What's new and committable:**
1. **Skip-equip in `runOutfitBootPhase`** — gated on `KNES_FF1_BOOT_EQUIP=1`, default skip. Saves ~240 useless A-taps × 4 chars between buy and strategic loop.
2. **`boot_exit_interior_result` trace** — captures exit ok/msg/world/mapId/mapflags + `via=vision|bfs` so post-buy exit state is visible from `trace.jsonl` alone.
3. **`strategy_grind_result` + `strategy_grind_anchor_shift` traces** — `GrindLoop` outcomes (EncounteredBattle / NoEncounter / Blocked / WanderedOff) now in trace. Previously only `println` to stdout — invisible in run dirs.
4. **Post-buy savestate dump** — `/tmp/spec5-post-buy.savestate` written immediately after `boot_purchase_advisor_done`. Skips the 5-min buy phase on subsequent dev iterations.
5. **Vision-LLM exit wired post-buy** — `runOutfitBootPhase` now uses `WalkInteriorVision(outfitNavigator)` (max 60 steps) when navigator configured, falling back to `ExitInterior` BFS only for offline/test runs. Pre-flight 8 B-taps to dismiss any lingering shop dialog before exit attempts.
6. **`ExitInterior` town-overlay fallback** — `walkOutOfTownOverlay` for the `mapId=0 + mapflags.bit0=1` case (used by `SkillRegistry`/`ExitBuilding` paths that don't have a navigator). Blind cardinal walker with screenshot dumps to `/tmp/spec5-town-exit-{entry,mid,exit}.png`. **Known limitation: insufficient for Coneria** — works for first 4 tiles south, deadlocks at courtyard wall. Acceptable as defensive fallback only; vision-nav is the primary path.

**What's still failing in cont-3 validation runs:**
- Vision-LLM nav (`WalkInteriorVision`) **also** fails to exit Coneria after 30 steps in cont-3 run #4 (`mapflags=3` at end). Bumped to 60 in this commit but un-validated. Possible causes: (a) insufficient iterations, (b) navigator prompt doesn't ban training-data Coneria geography (per `feedback_locate_party_first.md`), (c) party walks into a building doorway and gets stuck on dialog.
- The strategic loop's `UnknownMapTrap` detector at `AgentSession.kt:314` **still bails after 3 obs** when stuck in town overlay. This is a safety valve (clean OutOfBudget exit) but means: if vision-exit fails, the run terminates within ~5 turns post-buy. Tried adding a TOWN_ENTRY whitelist; it caused an infinite loop burning advisor LLM calls. **Reverted.** Properly fixing requires splitting trap detection from the grind-anchor capture, not done in this commit.

## 2026-05-09 cont 3 FINAL — HONEST STATUS: stuck in town across runs, scratchpad infrastructure built, exit + encounter UNRESOLVED

**User-corrected reality (per visual screenshot evidence at 21:24):** despite extensive iteration with tree-detour heuristics, the agent **remains stuck in Coneria town** at the end of multiple runs. The "exit success" RAM signal (`mapflags=0`) was misleading — sometimes it briefly clears mid-scroll then reverts, sometimes the agent is structurally still in town overlay. Running 8+ iterations of fixes (tree-detour, approach-to-(14,22) pre-nav, mapflags-bit-1 settle waits, longer post-exit walks) did NOT produce a reliable end-to-end exit + grind + battle pipeline.

**What's solid (committable):**

1. **`AgentScratchpad`** — coding-agent-style action notebook persisted alongside the savestate (`/tmp/spec5-post-buy.savestate` ↔ `/tmp/spec5-post-buy.actions.json`). Records phase markers + cardinal taps with smPre/smPost/mapflagsPost. `walkInEntryTile()` returns the warp coord; `reverseTrajectoryFromTownEntry()` returns the reversed walk-in cardinals. `loadSister()` reads back on next run startup. Skips empty walk-in pairs from savestate-load skip cycles.
2. **Walk-in trajectory captured perfectly** — fresh-run scratchpad shows `[16,23] → UP×9, LEFT×1, UP×1, LEFT×1, UP×1, LEFT×2, UP×1, LEFT×1, UP×1 → [11,10]`.
3. **Post-buy savestate dump** at `boot_post_buy_savestate_dumped` — fast-iter (~30s) without re-running buy advisor.
4. **Vision-LLM hint API** — `historyHint: String?` parameter wired through `VisionInteriorNavigator.nextDirection()` and `WalkInteriorVision`. Prompt includes scratchpad render with directive "use as reasoning hint, not literal replay". Plus encoded "GRIND/ENCOUNTER zone = solid GREEN grass tiles, NOT stone walls or trees".
5. **Per-step screenshot evidence dumps** — `tree-detour-NN-DIR-smX_Y-mfN.png`, `postexit-NN-DIR-wX_Y-mfN.png`, `grind-stepNN-wX_Y-mfN.png`. Visual proof of every cardinal tap; user can audit.
6. **Per-step `encounterCounter` log in GrindLoop** — confirmed encounterCounter stays at 0 throughout walks, suggesting either we're never on a real encounter tile OR `0x00F5` isn't the right byte for FF1 NES.
7. **GrindLoop docstring updated** — trees ARE walkable (encounter zone, not obstacles). Town/castle entry tiles ARE warps. Mountains/water block movement.

**What's broken (uncommitted scaffolding):**

- **`ExitTownEmpirical`** — empirical adjacency-explorer + tree-detour heuristic (U R R D D D from (14,22)). Sometimes works (run #N reached `mapflags=0` and walked post-exit to (146,159) on overworld grass), sometimes the empirical phase ends at a different position (cont-3 N at (13,14)) where tree-detour can't reach the warp, sometimes pre-existing mid-scroll state makes ALL taps no-ops. Approach-to-(14,22) pre-navigation was added but doesn't help when intermediate tiles block the approach. **Net: unreliable, not a path forward as-is.**
- **Post-exit south walk** — sometimes works (Down×4 reaches (146,159)), sometimes worldY doesn't change (south of (147,155) gate is blocked by water). Coneria peninsula geography is more complex than my hardcoded heuristics handle.
- **GrindLoop encounter rate** — 72+ steps walked across multiple grind cycles, `encounterCounter` always reads 0, no `EncounteredBattle` ever fires. Either this byte is wrong, or Coneria peninsula tiles around the gate genuinely have no encounters (FF1 has "safe zone" buffer near Coneria starting town), or the engine's per-step encounter check isn't being triggered by our tap pattern.
- **`UnknownMapTrap`** detector at AgentSession:316 still bails after 10 obs of `mapflags.bit0=1 + mapId=0`. With unreliable exit, every run ends with this trap.
- **TRAP_CONFIRM_THRESHOLD bumped 3→10**, GrindLoop corridor 6→2, GrindLoop maxSteps 12→24 — none of these tuning changes resolved the encounter-zero problem.

## What worked once but isn't reliable

In one specific run earlier this session, the full pipeline completed: tree-detour exit → 2 LEFT to (145,155) overworld grass → 5 GrindLoop UP steps → walked into Coneria CASTLE (mapId=8) → ExecutorAgent walked deeper into castle (mapId=24 throne). That sequence proved the pieces CAN connect, but the empirical exploration phase that precedes tree-detour is non-deterministic — different starting positions for tree-detour cause different outcomes.

## Recommended next-session direction

Fundamentally, we need a navigator that doesn't depend on heuristic hardcoded sequences. Options:

1. **Vision-LLM exit only** — drop `ExitTownEmpirical` + tree-detour entirely. Use `WalkInteriorVision(navigator, historyHint=scratchpad)` as the exclusive exit path. The historyHint API is already wired. Audit + upgrade `AnthropicVisionInteriorNavigator`'s system prompt for town overlays specifically. Refresh the model ID — current default (`claude-opus-4-5-20251101`) may be invalid.
2. **Read PPU nametable for real walkability** — currently `mapId=0` decodes to overworld tile data via `InteriorMapLoader`, useless for town overlays. The town's actual tile layout lives in PPU nametable RAM (`$2000-$23FF`). Add a `TownOverlayTileSource` that reads PPU and classifies tiles. Then BFS works for `mapId=0+mapflags.bit0=1`.
3. **Investigate FF1 encounter mechanic** — what's the real RAM byte for encounter timer? Disasm research on `$00F5` and surrounding bytes. Is there a specific CPU instruction we need to step through to trigger encounter checks? Maybe our tap+settle pattern bypasses the engine's step-based encounter check.

## Carried over (kept below for context)

## 2026-05-09 cont 3 FINAL — EXIT CONERIA WORKS via tree-detour + scratchpad infrastructure

**The breakthrough:** user-provided tree-detour hint after observing screenshot of stuck state — `Up Right Right Down Down` from (14,22). With pressFrames=6 (1-tile precision), this navigates around the south-edge tree obstacle to the warp tile (16,23), where one more DOWN commits the transition: `mapflags 1 → 0` = on overworld!

```
[exit-town-empirical] tree-detour starting from smPlayer=(14,22)
[exit-town-empirical] tree-detour[0] Up    → smPlayer=(14,21) mapflags=1
[exit-town-empirical] tree-detour[1] Right → smPlayer=(15,21) mapflags=3 (mid-scroll)
[exit-town-empirical] tree-detour[2] Right → smPlayer=(16,21) mapflags=3 (mid-scroll)
[exit-town-empirical] tree-detour[3] Down  → smPlayer=(16,22) mapflags=1
[exit-town-empirical] tree-detour[4] Down  → smPlayer=(16,23) mapflags=1 ← warp tile
[exit-town-empirical] tree-detour[5] Down  → smPlayer=(16,23) mapflags=0 ← TRANSITION!
```

End-to-end pipeline now working from `/tmp/spec5-post-buy.savestate`:
1. ✅ Buy 4/4 (47-79 advisor iters fresh, instant from savestate)
2. ✅ Exit Coneria via tree-detour (6 cardinal taps)
3. ✅ Post-exit walk to overworld grass at (145,155) via 2 LEFT
4. ✅ GrindLoop walks 5 tiles north on overworld (encounters can fire)
5. ⚠️ At y=152 walks into Coneria CASTLE entry — encounter rate didn't fire in 5 steps (~50% probability)

**The mapflags=3 mystery solved:** bit 1 = "column-drawing" (PPU mid-scroll). It's TRANSIENT — drops back to 1 (or 0 after exit) once the scroll completes. cont-3 #4-#7 ran into mid-scroll states and falsely concluded "stuck"; the engine was fine, just sampled mid-frame. Tree-detour with shorter pressFrames (6 not 18) and 60-frame settles avoids the false-stuck.

## Remaining tuning (not blocking)

- **GrindLoop corridor radius bumped 6→2** in this session to stay y=153-157 between town south and castle north. With anchor at (145,155), corridor is safe. But the ADAPTIVE shift logic (after 3 NoEncounter, shift +2E -4N) moves anchor to (149,151) which puts corridor=(149-153) into castle territory again. Either disable shifts or change shift vector.
- **Encounter rate near Coneria gate appears very low** — 3 cycles × 24 steps = 72 walks at corridor (145,153-157) with NO encounter triggered. Either FF1 encounter rate is genuinely low here (classic grind is at the bridge area further north, not town entry), OR GrindLoop's UP/DOWN at this corridor re-enters town overlay each step (mapflags=1 transient — mapId stays 0, so GrindLoop's `mapId != 0` check doesn't catch). Worth verifying with `encounterCounter` RAM byte over time.
- **`grindAnchor` captures stale worldX** — anchor=(147,155) when party already at (145,155) post-exit walk. Engine has ~1-2 frame lag in updating worldX after warp. Fix: re-read RAM after a brief settle before capturing anchor.
- **After castle re-entry, ExecutorAgent's ExitInterior walks DEEPER** (mapId=8 → mapId=24 throne) instead of out. ExitInterior's BFS picks "nearest exit" but throne stairs may be closer than south door. Needs distance + south-bias.
- **TRAP_CONFIRM_THRESHOLD bumped 3→10** to give anchor-shift logic time to fire.

## 2026-05-09 cont 3 EARLIER PROGRESS — scratchpad infrastructure built (kept below for context)

**Architectural breakthrough (committable):**

`AgentScratchpad` (knes-agent/src/main/kotlin/knes/agent/runtime/AgentScratchpad.kt, 145 lines) — coding-agent-style action notebook that:
- Records every meaningful agent action: phase markers, cardinal taps with smPre/smPost, dialogs, exits
- Persists alongside savestate as sister `*.actions.json` (e.g. `/tmp/spec5-post-buy.savestate` ↔ `/tmp/spec5-post-buy.actions.json`)
- Loads on next run startup (Main.kt handles sister-file detection)
- Provides `renderForLLM()` for prompt injection, `walkInEntryTile()` for warp coord, `reverseTrajectoryFromTownEntry()` for cardinal replay
- Skips empty walk-in pairs (savestate-load skip pairs that have no taps)

The walk-in is captured perfectly. Fresh run output:
```
walk-in: 18 taps
  start smPlayer: [16, 23]   ← warp tile (town entry)
  end   smPlayer: [11, 10]   ← shop tile
Path: UP×9, LEFT×1, UP×1, LEFT×1, UP×1, LEFT×2, UP×1, LEFT×1, UP×1
```

Vision-LLM hint API (also wired): added `historyHint: String?` parameter to `VisionInteriorNavigator.nextDirection()`; the implementation prepends scratchpad render to the user prompt with the directive "use as reasoning hint, not literal replay" per user direction.

`ExitTownEmpirical` skill (knes-agent/src/main/kotlin/knes/agent/skills/ExitTownEmpirical.kt, 200+ lines) — deterministic adjacency-mapping explorer with Manhattan-gradient bias toward the warp coord from scratchpad.

**The wall hit (uncommittable, root-cause unsolved):**

The post-buy exit deadlocks at smPlayer `(13,22)`/`(14,22)` with `mapflags=3` (bit 0 set: in-interior; bit 1 set: PPU "column-drawing" per FF1 profile). **No input clears bit 1**:
- 300-frame idle waits: no clear
- 32 B-spam taps: no clear
- Multi-tap DOWN with settle: no clear
- Long-held DOWN (24 frames): no clear

`/tmp/spec5-mf3-stuck-14-22.png` screenshot shows the party VISUALLY on overworld grass at the south edge of Coneria, with the INN sign visible above. The camera has scrolled out, but the engine state is frozen. **All cardinals at this position return smPlayer-unchanged** (the engine literally ignores input in this state) → empirical explorer marks them as walls → exhausts edges → bails.

This happens in BOTH fresh runs (no savestate) AND savestate-loaded runs. It's not a savestate-restoration artifact; it's a real FF1 engine state we don't know how to commit. The walk-in tile `(16,23)` is presumably the only tile that would commit the transition (warp-out), but the empirical south-walk lands at `(14,22)` which is one tile west and one row north of the entry — close, but the engine treats it as "non-warp tile" and refuses to transition. The agent can't walk from `(14,22)` to `(16,23)` because at mapflags=3 cardinals are no-ops.

## Empirical evidence — what's actually happening at the deadlock

User-provided hint: **"to exit the city you need to go down from shop"**. We did. Six runs in cont-3 testing different exit strategies, with the following progression of evidence:

| Run | Strategy | Outcome | Evidence |
|---|---|---|---|
| #1 | BFS pathfinder over mapId=0 | 64 useless iters, oscillation | trace `did not exit interior in 64 steps` |
| #2 | Cardinal: DOWN→RIGHT→LEFT→UP rotate | DOWN to y=14, RIGHT side-step → mapflags=3 | (12,14) opens building dialog |
| #3 | Cardinal: DOWN→LEFT→RIGHT→UP rotate | DOWN to y=14, LEFT side-step → mapflags=3 | (10,14) opens building dialog |
| #4 | Vision-LLM `WalkInteriorVision` | 30 LLM calls, mapflags=3 final | LLM walks into building too |
| #5 | DOWN-only, NPC patience (5 idle-waits) | 17 stuck taps at (11,14), no progress | mid screenshot shows dialog, was an artifact of run #2/#3 not this run |
| #6 (this) | DOWN-only, full RAM dump at deadlock | Diff entry-vs-stuck: ONLY `localY` and `smPlayerY` change | **all 117 watched bytes identical except party position** |

**Definitive ground truth from run #6:** at smPlayer=(11,14) pressing DOWN, NO byte changes. mapflags=1, screenState=0, menuCursor=0, menuHandX/Y=0 — all stable. The wall at (11,15) is just a wall. **The "Welcome" dialog in `/tmp/spec5-town-exit-mid.png` was an artifact of LEFT/RIGHT side-stepping in earlier runs — those moves walked into the weapon-shop and inn doorways, opening dialog (mapflags=3). DOWN-only never opens any dialog.**

## Why cardinal walking is fundamentally insufficient

The Coneria south town exit is **NOT on column x=11**. From (11,10), DOWN reaches (11,14) and hits a hard wall. Both LEFT and RIGHT at y=14 are building doorways. The actual south overworld exit requires walking RIGHT (or LEFT) through the courtyard at higher Y, then DOWN past the buildings. The exact non-cardinal route requires map awareness — either:
1. Vision-LLM with a much better prompt (run #4 attempted but failed in 30 iters; locate-party-first per memory may not be enough)
2. Hardcoded route knowledge from manually mapping Coneria
3. Decoding the actual mapId=0 town overlay tile data — but `mapId=0` decodes to OVERWORLD tiles, not town tiles. The town overlay's tile data lives somewhere else (PPU nametable or a different RAM region) and isn't currently exposed to the agent.

## Next path forward

The Coneria-exit problem is **map-aware navigation**, not dialog detection or NPC patience. Both rabbit holes were thoroughly explored and ruled out by run #6's full-RAM diff.

**Recommended next-session moves, in order (autonomy-compatible — agent plays, dev does not, per `autonomy_principle.md`):**

1. **Build an "empirical Coneria explorer" skill.** Loop from (11,10): for each cardinal, tap → check if smPlayer changed → if yes, record edge (X,Y)→(X',Y') in adjacency map; if no, mark blocked; if mapflags.bit0 cleared, that's an exit tile — record. After exploring breadth-first up to N tiles, BFS the recorded adjacency for shortest path from (11,10) to any exit tile. Then drive the party along that path. Bonus: detect dialog opens (mid-walk RAM dump diff vs entry — currently ALL bytes stable so dialog opens are detectable as "any byte that flipped"; from `/tmp/spec5-ram-diff.log` we know the baseline).
   - Pure deterministic logic, zero LLM cost, runs offline. Cost: ~30-60 min of Kotlin code in a new `ExploreTownOverlay` skill.
   - This approach gracefully handles ANY town overlay, not just Coneria — same logic works for Pravoka, Elfheim, etc. once we get there.

2. **Upgrade `VisionInteriorNavigator` prompt** (`knes-agent/src/main/kotlin/knes/agent/perception/VisionInteriorNavigator.kt`, 245 lines) for town overlays. Current prompt is castle/dungeon-oriented. Add a town-specific path: enforce locate-party→locate-south-exit→derive-direction (per `feedback_locate_party_first.md`), ban training-data Coneria geography (per same memory), emphasize "walk AROUND buildings, not through them — building entrances LOOK like exits but aren't". Pass `ToolCallLog` for per-step visibility. This is the right primary tool per `feedback_vision_primary.md` IF empirical explorer (option 1) proves too slow.

3. **Long-term:** expose town-overlay tile data via a `TownOverlayTileSource`. Currently `mapId=0` decodes to OVERWORLD tile data (which is why BFS run #1 found bogus exits). The town overlay's tile data lives in PPU nametable — read it at $2000-$23FF region and convert to walkable/blocked tile classifications. Once available, normal BFS works for `mapId=0+mapflags.bit0=1`.

4. **After exit lands**: validate GrindLoop fires battle. Strategic loop is already wired and traced. `rm ~/.knes/ff1-ow-memory.json` to clear bogus warp record at (147,155) from cont-3 failed runs before validating.

5. **EquipWeapon revisit** — separate follow-up; not on the critical path. Bare-handed grinding works.

**Fast-iter setup carried into next session:**
- `/tmp/spec5-post-buy.savestate` — load via `KNES_FF1_LOAD_SAVESTATE` to skip the 5-min buy advisor (~30s startup)
- `/tmp/spec5-town-exit-entry.png` — what the agent sees post-buy at smPlayer=(11,10)
- `/tmp/spec5-ram-diff.log` — full per-tap log from cont-3 #6 run with all 117 watched bytes dumped at entry + every stuck position. Use as ground truth.
- `/tmp/spec5-town-exit-mid.png` — kept for reference but **not** evidence of dialog state in current code path; was a side-step artifact from cont-3 #2/#3.

## Known-broken interactions (notes for the would-be fixer)

- **Naive trap-whitelist + grindAnchor gate creates infinite loop.** Trap whitelist (skip bail near TOWN_ENTRY) without an alternative bail → `UnknownMapTrap` never fires. If `grindAnchor` capture is also gated on `mapflags.bit0=0` with `continue` on miss, the strategic loop spins: phase observe (vision call!) → strategic advisor (LLM call!) → not on OW → continue. ~$1/min. **Both reverted in this commit.** Future fix needs to split trap-bail from grind-anchor capture, e.g. add a separate "stuck-in-town for N obs" bail that doesn't poison the persisted warp memory.
- **Persisted warp memory `(147,155)`** — failed cont-3 runs wrote this to `~/.knes/ff1-ow-memory.json`. Subsequent runs preload it but the trap detector uses an in-memory set, so it's mostly cosmetic. Worth `rm ~/.knes/ff1-ow-memory.json` before validating the exit fix.

## Files changed this cont session (uncommitted)

- `knes-agent/src/main/kotlin/knes/agent/runtime/AgentSession.kt` — items 1–5 above
- `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt` — item 6 above
- `HANDOFF.md` — this file

## Earlier session (2026-05-09 cont 2 — unchanged below)

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
