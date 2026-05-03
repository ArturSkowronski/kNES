# V3.1 verification live run — 2026-05-03

**Outcome:** `OutOfBudget` after ~12 min.
**Trace:** `2026-05-03T14-53-42.928680Z/trace.jsonl`
**Cost estimate:** ~$0.45 (19 vision-navigator calls + 5 advisor calls + executor turns).

## TL;DR

V3.1 RAM hard-override worked: agent reached **Indoors(mapId=8, localX=5, localY=28)**
— the first time in the V2.4–V3.0 lineage that a real Coneria Town interior was
classified correctly without phase-classifier oscillation.

V3.0 vision-first interior nav, however, did NOT meet the DoD criteria:
- Step success on real-interior taps: **1 / 4 = 25 %** (V2.6.5 decoder baseline: 13 %)
- Exits via `walkInteriorVision`: **0** (no `transitioned=true` step entries).

The bottleneck is **navigator over-reporting STUCK**: 15 of 23 direction
responses (65 %) returned `STUCK` from inside Coneria Town, suppressing taps.

## Counters

| Metric | Value |
|---|---|
| Total trace events | 19 |
| Phases reached | TitleOrMenu, Overworld(146,158), Indoors(mapId=8), Overworld(144,155) |
| `walkInteriorVision` invocations | 19 |
| Navigator direction responses | 23 |
| &nbsp;&nbsp;STUCK | 15 |
| &nbsp;&nbsp;SOUTH | 3 |
| &nbsp;&nbsp;EAST | 1 |
| &nbsp;&nbsp;EXIT | 4 |
| Mechanical taps fired (after non-STUCK/EXIT) | 4 |
| Taps where party moved | 1 |
| Taps that transitioned to overworld | 0 |
| Step success rate | **25 %** |

## What worked

- **V3.1 hard-override** (`locType=0 && mapId=0` ⇒ Overworld) eliminated the
  V3.0-slice-1 oscillation. Phase progression was monotonic this run.
- **Vision navigator still parses every response** (no UNCLEAR).
- **EXIT detection** when shown an overworld frame: 4 of 4 EXIT calls were
  actually overworld frames (visible from the phase change to Overworld(144,155)).
- **Skill loop terminates** correctly on STUCK and EXIT.

## What didn't work

### Navigator too conservative on town maps

In Coneria Town the screen shows:
- A dirt path winding between shops/houses.
- NPCs (red, blue, green-clad sprites) standing along the path.
- Building walls forming corridors.

The current navigator system prompt frames "walls / shop counters / NPCs blocking
the path" as **non-walkable**, but in FF1 towns the player *can* walk around NPCs
and through the visible dirt paths. The model is interpreting "I see lots of
walls and NPCs" as STUCK, when there's clear navigable terrain on screen.

**Direction distribution evidence**:
- 65 % STUCK suggests systematic over-caution, not random failure.
- When vision DID pick (SOUTH/EAST), it picked a plausible direction — those
  were valid attempts even if blocked physically.

### Step success at 25 % is uninformative

Only 4 mechanical taps fired in the entire run. Cannot confidently compare
against the 13 % V2.6.5 decoder baseline (which had ~583 taps). The 1-of-4
move could be tuning noise.

## Diagnosis

The navigator prompt was tuned (Task 0 feasibility) for **castle interiors**
where ornate features are NOT exits and the south corridor IS the way out.
That tuning is wrong for **town outdoor maps** where:
- There is no single corridor; navigation is open path-network.
- Buildings and NPCs are *obstacles to walk around*, not signs the area is unwalkable.
- Exit is the south edge of the visible area, but the model needs to traverse
  ~5–10 tiles to reach it through a winding path.

## Action items (V3.2)

Two pragmatic levers:

1. **Prompt tuning for towns specifically.** Detect from RAM whether the current
   `mapId` corresponds to a town vs castle vs dungeon (FF1 mapIds 0–4 ≈ towns,
   higher ranges ≈ castles/dungeons per Disch's notes — needs verification).
   Pass `mapType` hint to the navigator. Tell it that in towns, dirt paths between
   buildings ARE walkable; NPCs can be walked around; STUCK is reserved for genuine
   surrounded-by-walls scenarios.

2. **Loosen STUCK semantics.** When navigator says STUCK *immediately* (step=0)
   on the first call from Indoors, it has no movement evidence yet — treat as
   "pick a default direction" rather than terminate. Only honor STUCK after at
   least 2 failed taps.

Either lever is ~30 LOC. Recommend implementing both before next live run.

## Cost ledger

- ~19 navigator vision calls × ~$0.005 = ~$0.10
- ~5 advisor calls (Opus mid-run when STUCK looped) × ~$0.05 = ~$0.25
- ~19 executor turns (Sonnet/Haiku) ≈ ~$0.10
- **Total ≈ $0.45**, under budget.

Combined V3.0 slice 1 + V3.1 verification spend this session: ~$0.95.
