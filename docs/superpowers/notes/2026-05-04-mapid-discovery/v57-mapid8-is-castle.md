# V5.7 evidence: mapId=8 is Coneria CASTLE, not Town

## Experimental setup

`V56InteriorPathfindingTest` runs:
1. Live boot (PressStartUntilOverworld) → spawn (146, 158).
2. Raw-tap walk: UP×6, LEFT×1, UP×1 → enters interior with `$48=8`.
3. `ExitInterior(maxSteps=20)` with `InteriorPathfinder` (south-edge heuristic).

## V5.7 attempted fix

`InteriorPathfinder.findPath` modified to append `Direction.S` when finding
a south-edge exit, so agent walks off the boundary instead of stopping on it.

## Result (V5.7 attempt)

```
[0]  from=(11,32) dir=W → after=(10,32) mapId=8 pathLen=3
[1]  from=(10,32) dir=W → after=(9,32)  mapId=8 pathLen=2
[2]  from=(9,32)  dir=S → after=(9,32)  mapId=8 pathLen=1   ← engine refused
[3]  from=(9,32)  dir=S → after=(9,32)  mapId=8 pathLen=1   ← stuck
...
[19] from=(9,32)  dir=S → after=(9,32)  mapId=8 pathLen=1   ← stuck
```

Step success: 10% (was 100% before the attempt).

## Diagnosis

Heuristic `isSouthEdgeExit` identifies (9,32) as exit because rows 33-40
column 9 are all impassable per the InteriorMap decoder. **But the engine
refuses to walk SOUTH from (9,32)** — party stays on the same tile across 18
attempts.

This means (9, 32) is NOT a real exit from the engine's perspective. It's just
a passable tile bordering decoder-impassable cells.

## Implication: mapId=8 is Coneria CASTLE, not Coneria Town

FF1 design:
- **Towns** (Coneria Town etc.) have implicit south-edge exits — party walks
  off the bottom row and engine triggers transition. Our south-edge heuristic
  is correct for towns.
- **Castles/dungeons** (Coneria Castle etc.) have **explicit DOOR tiles**
  where party steps + presses A. No implicit south-edge exit — walking off
  the playable area is blocked.

Engine refusal at (9, 32) → mapId=8's exit semantics are CASTLE not TOWN →
mapId=8 is Coneria Castle.

This corroborates V5.5 research conclusion (decoded data + vision frames
showed castle layout). Three independent signals now agree:
1. Decoded ROM data shows walls 0x30 + floors 0x31 + STAIRS 0x44 (castle pattern)
2. V2.6.x vision-trace: "Castle courtyard, not town huts"
3. V5.7 engine-refusal at heuristic south-edge exit (no implicit exit)

## What this means for the agent

- The V5.6 foundation fix (sm_player_x/y) is correct and yields 100% step
  success. That's not in question.
- `ExitInterior` with `InteriorPathfinder` works for towns (implicit exits)
  but NOT for castles (need DOOR/STAIRS detection + A-tap).
- mapId=8 (Coneria Castle) is the wrong fixture for testing town nav. We need
  a fixture for actual Coneria Town (different mapId, TBD via Phase 3 sweep).

## Status

V5.7 attempt reverted. Foundation V5.6 unchanged.
`ExitInterior` retains the V5.7 tap pattern (1 tile per step, replaces
held-button batching) — that part is independent of the south-edge fix and
genuinely improves precision regardless of map kind.

## Next steps (deferred)

- **Phase 3** (originally proposed): empirical sweep to find mapId for true
  Coneria Town. Method: walk overworld until vision sees town huts, read $48.
  Likely candidates: small mapIds like 1, 2, 3 (each town has its own).
- **Castle exit logic**: scan InteriorMap[8] for DOOR/STAIRS tiles, route
  to one, A-tap. Or: read tile_prop bits from ROM (per Disch Constants.inc,
  tileset has `tileprop` bits indicating teleport/exit-trigger).
- **Anti-pattern lesson**: heuristic-based exit detection (south-edge) was
  always partial. Real fix needs ROM tile-property table or per-mapId hint.
