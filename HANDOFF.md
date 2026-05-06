# FF1 Koog Agent — Handoff (Phase 2 Garland-attempt session, 2026-05-06 afternoon)

**Branch HEAD:** `69c8e53` on `ff1-phase2-garland-attempt-hardening`. **PR #115 open** against `master`. 12 attempts to reach Garland; agent infrastructure substantially hardened, but Garland NOT defeated.
**Required env:** `ANTHROPIC_API_KEY` (Phase 2 vision is hardcoded to Anthropic in `Main.kt`).

---

## TL;DR — Phase 2 agent hardened across 12 iterations; Coneria peninsula remains a wall

Tried to make `:knes-agent:run` actually reach Garland. Pre-populated `LandmarkMemory` with derived bridge + Temple coords from ROM tile bytes. Each iteration uncovered the next bug; cumulative fixes landed in **PR #115**.

```
PR #115 (open) — fix(phase2): harden agent — postbattle cap, trap detection, frontier prompt
  WalkOverworldTo (V5.33) — 30-frame NOOP warmup + deferred fog.markBlocked
  AgentSession (V5.34/V5.34.2/V5.34.3) — top-of-loop budget, postbattle cap=150,
    confirmed UnknownMapTrap detection (3-obs threshold + Battle/PostBattle excl.)
  AdvisorAgent prompt — V5.32 frontier bias + V5.34.4 Coneria overlay nav + V5.34.5 XP grind
  ExecutorAgent — maxIterations 16 → 24
```

**Net effect:** infinite postbattle zombie loops → clean `OutOfBudget` termination; false-positive trap-bails on tile 0x00 FIGHT-normal grass eliminated; agent now reaches Coneria town overlay and uses `exploreInteriorFrontier` as designed (327 calls in v11).

---

## Verified live this session

| Fix | Live evidence |
|---|---|
| WalkOverworldTo WARMUP_FRAMES=30 (V5.33) | v5+ runs no longer flag input-dead on first overworld step post-spawn / post-battle. |
| WalkOverworldTo deferred fog mark (V5.33) | v5 evidence vs v4: agent stopped declaring false deadlock at (152,151) after one failed W step. |
| AgentSession postbattle cap (V5.34, 60→150) | v6 first clean OutOfBudget after 20 dismiss; v11 at 150 dismiss across 23 battles → cap = working but multi-screen postbattle dwarfs initial estimates. |
| Top-of-loop budget enforcement (V5.34) | wallClock check no longer skipped by `continue` branches. v6 ran 4m 7s and terminated cleanly. |
| Trap detection 3-obs threshold + phase exclusion (V5.34.3) | v9: 0 trap-bails despite phase=Indoors(mapId=0,isTown=true) Coneria overlay reached. v11: 0 trap-bails across 9m 44s + 23 battles. |
| Advisor frontier prompt (V5.32) | v4 advisor invoked findPath 69× (was 0); chose verified GRASS waypoint (168,144) instead of WATER (168,145). |
| Advisor Coneria-overlay nav prompt (V5.34.4) | v10/v11: agent used `exploreInteriorFrontier` 135× / 327× respectively instead of cycling exitInterior. |
| Pre-populated bridge + stepping-stone landmarks | Advisor visibly references EXIT_TILE(157,141) and EXIT_TILE(168,144) in turn-2 plan output. |

---

## Iteration progression

Spawn at world (146, 158); spawn → bridge = ~22 manhattan tiles; spawn → Temple = ~50.

| Iter | Outcome | Max y N | Battles | Postbattle | Trap-bail | Key change |
|------|---------|---------|---------|------------|-----------|------------|
| v2 | zombie loop | 153 | — | infinite | — | baseline |
| v3 | zombie loop | 151 | — | infinite | — | bridge landmark + maxIter 24 |
| v4 | zombie loop | 151 | — | infinite | — | frontier prompt + stepping stone |
| v5 | zombie loop | 149 | — | infinite | — | WARMUP + deferred fog |
| **v6** | **`OutOfBudget`** ✅ | 149 | — | 20 (cap) | — | V5.34 postbattle cap |
| v7 | trap-bail (false) | 153 | — | 0 | 1 | UnknownMapTrap detection (eager) |
| v8 | trap-bail (false) | 153 | — | 0 | 1 | pre-pop trap warps |
| **v9** | **`OutOfBudget`** ✅ | 149 | **51** | 60 (cap) | 0 | V5.34.3 confirmed trap detection |
| v10 | (Anthropic 60s timeout) | 149 | 5 | 0 | 0 | postbattle cap 60→150, town nav prompt |
| **v11** | **`OutOfBudget`** ✅ | 149 | 23 | 150 (cap) | 0 | retry of v10 |
| v12 | (killed for PR) | — | — | — | — | XP grind prompt (V5.34.5) |

Bridge (157, 141), mainland north of Coneria, Temple of Fiends (168, 117), Garland — **none reached**.

---

## Research finding: FF1 NES warp table (datacrystal + Entroper/FF1Disassembly)

The "hidden warp" hypothesis at (147,153)/(147,154) was wrong. Per `bank_0F.asm` (`GetOWTile` + `SetOWTileProps`), each visual tile_id 0x00–0x7F has a 2-byte property entry in `tileset_prop` (NES $8000 in BANK_OWINFO = file offset 0x10):

- byte 1 `[SSdf ascw]` = walking + vehicle restrictions
- byte 2: `$80` set → TELEPORT (low 6 bits = teleport_id, indexes `lut_EntrTele_X/Y/Map`); `$40` set → FIGHT (random encounter trigger)

Dumped the table for our ROM. Tile 0x00 has byte2 = `0x40` = **FIGHT-normal**, NOT teleport. The empirical (mapflags=1, mapId=0) state on stepping onto (147, 154) was a 1-frame battle-entry artifact, not a hidden warp. V5.34.3 (3-obs threshold + Battle/PostBattle exclusion) addresses this directly.

Real overworld warp tile_ids (byte2 & 0x80):
- 0x01–0x02 (id 9), 0x0E (id 14), 0x1B–0x1C (id 10), 0x1D (id 24, chime-gated),
- 0x29–0x2A (id 11), 0x2B (id 16), 0x2F (id 20), **0x32 (id 21 — Temple of Fiends)**,
- 0x34 (id 25), 0x35 (id 26), 0x38–0x39 (id 12), 0x3A (id 22), 0x46 (id 19, currently misclassified as BRIDGE),
- 0x49–0x4E (ids 1–5), 0x57–0x58 (id 13), 0x5A/5D (ids 6–7), 0x64/0x65 (id 15),
- 0x66–0x6E (ids 17, 27, 28, 29, 0, 18, 8, 23).

`OverworldTileClassifier` already buckets most of these as CASTLE/TOWN (impassable transit). Notable misclassifications:
- 0x46 currently → BRIDGE; should be WARP (post-Garland bridge cutscene teleport).
- 0x1D currently → UNKNOWN; could be WARP_CHIME_GATED.

Coneria warp at world (145, 152) tile = 0x76; tileset_prop[0x76] byte2 = 0x00 (no teleport). Coneria overlay activates via a different mechanism (proximity-based town overlay, not per-tile warp lookup) — separate codepath in the engine, out of scope of `tileset_prop`.

---

## Open work for next session

### Primary blocker: encounter density on Coneria peninsula

v9 + v11 evidence: spawn → bridge needs ~22 tiles of traversal. Random encounters fire every 4–8 tiles in grass/forest, producing 23–51 battles. Multi-screen postbattle (XP / level-up / gold) × 4 chars = 60–150 dismiss cycles per run. Even with `POSTBATTLE_DISMISS_CAP=150`, full budget exhausts before reaching the bridge.

**Candidate fixes (not yet implemented):**
- (a) **Savestate fixture past the bridge** (10 min manual): boot game, walk through Coneria, cross bridge, save state at world ~(180, 150). Set as `FF1_SAVESTATE` for Phase 2 runs that target Garland specifically.
- (b) **Waypoint-aware advisor planning**: V5.34.5 prompt added the *concept* of 8-10 tile waypoints, but advisor still defaulted to `walkOverworldTo(157,141)` end-to-end in v11. May need to enforce in code (split single long walks at intermediate fog frontiers).
- (c) **Wire Gemini into Phase 2** (1–2h, 3 new classes + Main.kt router) — better vision precision; not a root-cause fix for encounter density but would help downstream once past the bridge.

### Smaller / opportunistic
- Update `OverworldTileClassifier` to consume `tileset_prop` from ROM, so 0x46 → WARP (not BRIDGE) and 0x1D → WARP. Would let BFS treat all true warp tiles as impassable transit.
- Postbattle handler diagnosis: 23 battles × ~6.5 dismiss avg suggests level-up animations or cascading encounters mid-postbattle. Could add a per-battle dismiss counter (vs current consecutive cross-battle counter).
- `OverworldWarpMemory` has 1 entry: (145, 152) Coneria. The two false UnknownMapTrap entries from attempt8 were cleaned up.

---

## Code architecture (post-PR-#115)

```
knes-agent/src/main/kotlin/knes/agent/
  advisor/AdvisorAgent.kt
    systemPrompt — V5.32 FRONTIER, V5.34.4 CONERIA OVERLAY, V5.34.5 XP GRIND
  executor/ExecutorAgent.kt
    maxIterations = 24 (V5.23.2 update — was 16)
  runtime/AgentSession.kt
    Top-of-loop budget enforcement
    POSTBATTLE_DISMISS_CAP = 150 + consecutivePostBattle reset
    UnknownMapTrap detection: TRAP_CONFIRM_THRESHOLD=3 + phase!=Battle/PostBattle
      On confirmed trap: failedWarpTiles += tile, fog.markBlocked,
                          warpMemory.record(mapId=0) + save, return OutOfBudget
  skills/WalkOverworldTo.kt
    WARMUP_FRAMES=30 NOOP at skill start
    fog.markBlocked deferred to consecutiveNoMove >= 2
```

Explorer pipeline (`SingleRun.kt`, `ExplorerSession.kt`) unchanged this session — its #111/#113 trap-bail logic is what V5.34.2/3 ports into Phase 2.

---

## Test status (post-PR-#115)

`./gradlew :knes-agent:test` not re-run this session — last green was **233 pass / 2 pre-existing fail / 7 skipped** at master (`ca2972f`). PR test plan checkbox open.

---

## Pre-populated memory state

`~/.knes/ff1-landmarks.json` (16 entries; 2 manually added this session):
- `EXIT_TILE at world(157,141)` — N bridge from Coneria peninsula (manual ROM derivation).
- `EXIT_TILE at world(168,144)` — verified GRASS stepping stone S of Temple of Fiends.
- `DUNGEON_ENTRY at world(168,117)` — Temple of Fiends candidate.
- `DUNGEON_ENTRY at world(210,149)` — alternative candidate (Pravoka or Temple cluster).
- 12 NPC_KING / STAIRS_DOWN / TOWN_ENTRY entries from prior explorer runs.

`~/.knes/ff1-overworld-warps.json`: 1 entry — Coneria (145,152) → mapId=8. Two false trap entries from attempt8 were cleaned out after V5.34.3 fix.

---

## Useful CLI

```bash
# Phase 2 with bumped budget (matches PR #115 test plan).
./gradlew :knes-agent:run --args="--rom=/Users/askowronski/Priv/kNES/roms/ff.nes \
  --max-skill-invocations=200 --cost-cap-usd=10.0 --wall-clock-cap-seconds=1800"

# Explorer (unchanged, Gemini-capable).
KNES_VISION=gemini-pro ./gradlew :knes-agent:runExplorer

# Verify fog state mid-run by checking log for trap detector + postbattle counter.
grep -E "unknown-map-trap|postbattle auto-dismiss|OUTCOME:" /tmp/knes-garland-attempt*.log

# Inspect landmarks (incl. our manual additions).
jq '.landmarks[] | {kind, x: .worldX, y: .worldY, note}' ~/.knes/ff1-landmarks.json
```

---

## Repo paths

- Main: `/Users/askowronski/Priv/kNES`
- Branch: `ff1-phase2-garland-attempt-hardening` (HEAD `69c8e53`)
- PR: https://github.com/ArturSkowronski/kNES/pull/115
- ROM (gitignored): `/Users/askowronski/Priv/kNES/roms/ff.nes`
- Persistent memory: `~/.knes/ff1-{landmarks,overworld-warps,interior-memory,overworld-terrain,blockages,ow-memory}.json`
- Run logs (this session): `/tmp/knes-garland-attempt{2..12}.log`

Pre-session archive (rollback target if PR rejected):
- `~/.knes/archive-2026-05-06-pre-gemini-campaign/`

---

## Test fixtures

- `ff1-post-boot.savestate` — overworld at (146, 158); used by explorer, NOT by Phase 2 (Phase 2 boots from ROM intro).
- `ff1-coneria-interior-discovery.savestate` — inside mapId=8.

**Missing fixture for next session:** post-bridge savestate at world ~(180, 150) — would skip the encounter-dense Coneria peninsula entirely. See "Open work" section.

---

## First message to send to next session (suggestion)

> Branch `ff1-phase2-garland-attempt-hardening` at `69c8e53`, PR #115 open. 12 Phase 2 attempts this session — agent now terminates cleanly (no zombie postbattle, no false UnknownMapTrap), reaches Coneria town overlay, fights 23–51 battles per run. Garland not reached: encounter density on Coneria peninsula consumes the full postbattle dismiss budget (cap=150) before agent crosses the N bridge. **Highest-leverage next step: capture a savestate past the bridge and set `FF1_SAVESTATE` so Phase 2 runs targeting Garland skip the peninsula entirely.** FF1 disasm research dumped `tileset_prop` table — `OverworldTileClassifier` should consume it (0x46 is WARP not BRIDGE; 0x1D is WARP_CHIME_GATED) for true overworld passability. Conversation in Polish; PR-flow + tests-first + commit per closed phase.
