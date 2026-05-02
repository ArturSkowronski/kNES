# V2.4 evidence — interior decoder + phase work; exit BFS heuristic needs refinement

Run on 2026-05-02 ~19:40. Args: `--max-skill-invocations=30 --wall-clock-cap-seconds=600`.
Trace: `2026-05-02T19-40-08.093689Z/trace.jsonl` (15 events).

## Headline

**Half-success.** V2.4 infrastructure works end-to-end: ROM decoder, phase classification with `mapId`, ASCII map rendering for Indoors, and the new `exitInterior`/`findPathToExit` tools all integrate cleanly. The agent:

1. Boots → Overworld(146, 158) ✓
2. Walks north and **enters Coneria town** at world (145, 152) ✓
3. Phase correctly classified as **Indoors(mapId=8, localX=5, localY=28)** ✓ (V2.3.1 just said "Indoors", V2.4 adds the mapId — exactly Coneria town's ROM index)
4. Executor calls `exitInterior` ✓
5. `findPathToExit` reports a path 2 steps east to (7, 28) ✓
6. **`exitInterior` fails to actually exit** ✗ — repeated 4× with 100-300 step budgets

## What broke

`InteriorPathfinder.isSouthEdgeExit` flags ANY passable tile whose immediate south neighbour is impassable/UNKNOWN as a candidate exit. **Inside Coneria town this fires on internal walls** — e.g. (7, 28) is a floor tile but the next row south has a wall segment. BFS picks this nearest "exit", agent walks 2 east, but the engine doesn't transition (because it's not actually the south edge of the playable area, just a step into another building's outline).

The actual Coneria town exit is at row ~32-34 of the map, where the playable area ends and `0x39` (outside-of-map padding) begins on all rows below. Pathfinder needs to detect "south boundary of the WHOLE playable area" not "any passable tile with non-passable south neighbour".

## What worked (concretely)

- `currentMapId` byte at `$0048` correctly identified by Task 4 research diagnostic. Coneria town = mapId 8 confirmed in trace.
- `InteriorMapLoader` decoded all 64 maps without crashing (Task 6 live test).
- `InteriorTileClassifier` correctly identified floor (0x21, 0x31), walls (0x30, 0x32-0x35), padding (0x39).
- `MapSession.ensureCurrent` properly cached and triggered fog-clear on transition.
- `AdvisorAgent` rendered Indoors ASCII to its prompt — the advisor saw the actual interior layout when invoked at turn 4.
- `findPathToExit` returned a deterministic non-trivial path (consistent across multiple calls) — i.e. the BFS itself is correct, just over the wrong notion of "exit".
- Phase classifier evolution (V2.1 → V2.3.1 → V2.4):
  - V2.1: "Indoors" wasn't even a phase
  - V2.3.1: "Indoors(localX, localY)" — knew we were inside something but not which interior
  - V2.4: "Indoors(mapId=8, localX=5, localY=28)" — correctly identified Coneria town

## Comparison to V2.3.1

| Metric | V2.3.1 | V2.4 |
|---|---|---|
| Reaches Coneria interior? | yes (1 turn) | yes (2 turns) |
| Phase says which interior? | no — generic Indoors | YES — mapId=8 (Coneria town) |
| Decodes interior map from ROM? | no | YES |
| Calls exit skill? | yes (`exitBuilding`, walked SOUTH) | yes (`exitInterior`, BFS-driven) |
| Combat fought? | 5 battles in castle | none — stuck before encounters |
| Exits to overworld? | no | no |
| Reason for failure | castle has stairs/sub-maps not modelled | exit BFS fires on internal walls |

V2.4 is a different failure mode than V2.3.1. The agent wasn't even getting into combat — it was thrashing on a false-positive "exit" 2 tiles away. Net progress: less far in this specific run, but the infrastructure (mapId, ROM decoder, deterministic pathfinder over interior) is now in place for V2.4.1 fix.

## V2.4.1 fix scope

Refine `InteriorPathfinder.isSouthEdgeExit` to only return true for tiles on the actual outer south boundary of the playable area:

```kotlin
// Detect "outer south boundary": passable tile, AND every column in the same
// row south of it is either out-of-viewport or impassable AT ALL DEPTHS down
// to the viewport bottom.
private fun isSouthEdgeExit(viewport: ViewportMap, lx: Int, ly: Int): Boolean {
    val type = viewport.tiles[ly][lx]
    if (!type.isPassable()) return false
    // Walk southward; if we encounter passable terrain again, this isn't the boundary.
    for (sy in ly + 1 until viewport.height) {
        if (viewport.tiles[sy][lx].isPassable()) return false
    }
    // And the south-most tile must be impassable/UNKNOWN (or off-viewport).
    return true
}
```

This eliminates internal-wall false positives. Test: a town map's actual exit row is the only row where every column-south is impassable/padding all the way down.

Also worth considering: tile id 0x44 (currently classified as STAIRS) may not actually be stairs in towns. Verify per-map by manually mapping known tile ids.

## Files referenced

- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMap.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorMapLoader.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/InteriorTileClassifier.kt`
- `knes-agent/src/main/kotlin/knes/agent/perception/MapSession.kt`
- `knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt` (← V2.4.1 will tweak `isSouthEdgeExit`)
- `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt`
- `knes-debug/src/main/resources/profiles/ff1.json` (`currentMapId` at 0x0048)
