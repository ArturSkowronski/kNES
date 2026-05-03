# V2.4.1 evidence — south-edge fix unlocks deeper navigation

Run on 2026-05-02 ~21:46. Args: `--max-skill-invocations=30 --wall-clock-cap-seconds=600`.
Trace: `2026-05-02T21-46-10.310074Z/trace.jsonl` (16 events).

## Headline

**V2.4.1 fix works — partially.** The refined `isSouthEdgeExit` (whole-column-south check) eliminates the false-positive that V2.4 hit at (7, 28). Live evidence:

1. Boot → `Overworld(146, 158)` → walks north into Coneria → `Indoors(mapId=8)`.
2. **V2.4 stuck at localY=28**. **V2.4.1 reaches localY=42** (14 tiles deeper south inside Coneria town map).
3. Eventually transitions to `Indoors(mapId=24)` — a different interior (likely Coneria Castle entrance).
4. Stuck again in mapId=24, OutOfBudget after 30 skills.

So V2.4.1 fix unblocks the immediate false-positive. The agent now walks south through the playable area and triggers a map transition. **But the transition lands in another interior, not the overworld**, and the new interior's pathfinder also fails to find the way out.

## What V2.4.1 fixes (confirmed)

`isSouthEdgeExit` no longer fires on internal walls. The agent successfully navigated south through Coneria town's playable area (localY 28 → 42) — V2.4 baseline got stuck after 0 tile progress at localY=28.

## What's still broken

**Multi-map transitions confuse the pathfinder.** Walking south in Coneria town didn't land on overworld; it landed in mapId=24 (likely a sub-interior — perhaps the screen between town and castle, or castle entrance). The pathfinder for mapId=24 then can't find an exit either.

Two failure modes converge:
1. **Tile classification gaps** — mapId=24 may use tile ids the classifier doesn't know yet (built only from mapId=8).
2. **Exit topology** — V2.4 assumed "interior → overworld" is one-step. Reality is "interior → maybe-another-interior → overworld" via stairs/transitions. The `MapSession` correctly reloads on transition, but the classifier's UNKNOWN bucket on unfamiliar tiles makes BFS fail.

## Comparison to V2.4 baseline

| Metric | V2.4 baseline | V2.4.1 (this run) |
|---|---|---|
| Phase classifier (mapId) | yes | yes |
| Reaches Coneria town | yes (localY=28, stuck) | yes |
| **localY progress in town** | **28 (0 tiles)** | **42 (+14 tiles)** |
| Triggers map transition | no | yes (mapId 8 → 24) |
| Reaches overworld? | no | no |
| Failure mode | false south-edge exit | classifier gaps in second interior |

V2.4.1 is real progress: the south-edge heuristic now distinguishes outer playable boundary from internal walls, and the engine successfully fires sub-map transition. The remaining gap is generalising the classifier to handle ALL interior maps, not just Coneria town.

## V2.4.2+ scope

1. **Multi-map classifier coverage**: dump tile ids appearing in mapIds 0..30 (covers Coneria town/castle, Pravoka, dungeons), build a per-mapId classifier table OR a wider lookup that handles all observed ids.
2. **Map graph awareness** (V2.5): if `currentMapId` keeps changing without reaching overworld after N transitions, advisor should consider that we're stuck in a multi-map labyrinth and try a different overworld approach (e.g. don't walk into towns at all).
3. **CHR-aware classifier**: FF1 may use the same byte id for different semantics depending on which CHR bank is mapped. Check whether mapId=24 reuses ids differently.

## Files touched in V2.4.1

- `knes-agent/src/main/kotlin/knes/agent/pathfinding/InteriorPathfinder.kt` — `isSouthEdgeExit` checks whole-column-south
