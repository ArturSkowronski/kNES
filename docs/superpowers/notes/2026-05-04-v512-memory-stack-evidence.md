# V5.12 evidence — InteriorMemory stack on Coneria castle

**Date:** 2026-05-04
**Branch:** `ff1-agent-v2-4` HEAD `3460174`
**Test:** `knes.agent.perception.V58InteriorMemoryLiveTest`
**Run:** live boot, no API key, ~3s wall

## What was being tested

End-to-end smoke of the V5.8→V5.10 stack (V5.11 forced-exploration prompt
deliberately not exercised — needs live vision navigator):

1. `ExitInterior` writes observations to `InteriorMemory` per step.
2. JSON file persists via `save()` in `finally{}`.
3. Cross-session `InteriorMemory(file)` reload matches in-memory state.
4. `InteriorPathfinder` with pre-populated `POI_STAIRS` selects it over
   the V5.7 south-edge fallback.

## Captured stdout

```
[v58-live] entered interior: mapId=8 party=(11,32)
[v58-live] ExitInterior(maxSteps=20) → ok=false message="did not exit interior in 20 steps"
[v58-live] memory.visited(mapId=8).size = 16
[v58-live] memory.pois = [POI_STAIRS@(12,18)]
[v58-live] memory file: /var/folders/.../v58-mem-...json exists=true size=3033
[v58-live] roundtrip: reload visited.size=16
[v58-live] phase C noMem: found=true reached=(10, 18) steps=4 reason=null
[v58-live] phase C: pre-populating POI_STAIRS at (14, 17) (1 step NORTH)
[v58-live] phase C withMem: found=true reached=(14, 17) steps=1
```

## What this proves

- **V5.9 wiring works on real ROM.** Agent autonomously stepped onto a
  STAIRS tile (0x44) at world (12, 18) inside mapId=8, the side-effect
  in `ExitInterior` recorded `POI_STAIRS` for that coord, and `save()`
  wrote it to the JSON file — all without manual seeding.
- **V5.10 priority works on a live viewport.** With nothing in memory,
  the pathfinder routes 4 steps SOUTH to the south-edge fallback at
  (10, 18). With a single `POI_STAIRS@(14,17)` pre-populated, the
  pathfinder routes 1 step NORTH directly to that tile. POI bucket
  beats south-edge bucket exactly as designed.
- **Cross-session persistence works.** Reloading from the JSON file
  yields the identical `visited(mapId)` set.

## Important new finding — POI_STAIRS in castles ≠ exit

`ExitInterior` reached STAIRS@(12,18), tapped A (the existing `STAIRS or
WARP → tap A` branch), and got NO transition to overworld:

```
ExitInterior(maxSteps=20) → ok=false message="did not exit interior in 20 steps"
```

Confirms HANDOFF V5.7 blocker #2 hypothesis empirically: in FF1 castles
the STAIRS tile (0x44) navigates to a different sub-map (going up/down
floors) — it is **not** the way out to the overworld. Towns may behave
differently (south-edge implicit exit) but the castle pattern is now
firmly evidenced.

### Implication for V5.x semantics

Recording STAIRS-touched tiles as `POI_STAIRS` and then preferring them
in the pathfinder is **correct for "interesting tile to step on"**, but
**incorrect for "way out of this map"**. With the V5.10 priority order
as currently coded:

```
EXIT_CONFIRMED > POI_* > viewport DOOR/STAIRS/WARP > south-edge
```

a fresh agent in Coneria castle will repeatedly path to STAIRS@(12,18),
A-tap, end up on a different sub-map, find a new STAIRS there, etc.
Never reaching the overworld unless one of the sub-maps happens to have
a south-edge exit OR the agent burns enough steps to record an
`EXIT_CONFIRMED` somewhere.

### Options to fix (not yet decided)

1. **Split the enum.** Introduce `POI_SUBMAP_STAIRS` (records seeing 0x44
   without further information) vs `POI_EXIT_STAIRS` (only set after
   stepping there caused mapflags bit 0 to clear, i.e. a confirmed exit
   transition). Pathfinder priority becomes
   `EXIT_CONFIRMED > POI_EXIT_STAIRS > POI_DOOR > POI_WARP > viewport... > south-edge >
   POI_SUBMAP_STAIRS`.
2. **Per-tile "leads-out" flag.** Keep the enum but add a boolean
   `confirmedLeadsOut` set true only on `EXIT_CONFIRMED` propagation.
   Pathfinder skips POI_STAIRS where flag is false.
3. **Track sub-map history.** When stepping onto POI_STAIRS triggers a
   `currentMapId` change (not mapflags clear), record the destination
   mapId in the fact's note. Lets the agent reason "STAIRS@(12,18) leads
   to mapId=9, not overworld" and avoid the loop on subsequent runs.

Option (1) is the cleanest standalone change; (2) is the smallest delta;
(3) is the richest data but requires tracking transitions between
sub-maps in the wiring.

## Anti-pattern reminder

Per V5.7 evidence note: don't pile workarounds on this. If the agent
gets stuck looping STAIRS→submap→STAIRS, STOP and pick one of the three
options above before adding more priority rules.

## Files involved

- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMemory.kt`
  — V5.8 layer
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt`
  — `0x44 → STAIRS`
- `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt`
  — V5.9 wiring; the `STAIRS / WARP → tap A` branch records POI_STAIRS
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt`
  — V5.10 priority
- `knes-agent/src/test/kotlin/knes/agent/perception/V58InteriorMemoryLiveTest.kt`
  — this evidence
