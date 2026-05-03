# V2.4.5 evidence — frames=48 not the bug; viewport-too-small at map-edge spawn

Run on 2026-05-03 ~05:54. Args: `--max-skill-invocations=40 --wall-clock-cap-seconds=720`.
Trace: `2026-05-03T05-54-03.420661Z/trace.jsonl` (18 events).

## Headline

**Different failure mode** with frames=48. Party spawned at
`Indoors(mapId=0, localX=7, localY=6)` — likely Coneria Castle ground floor,
near the top-left corner of the 64×64 map. Every `findPathToExit` call this run
returned `BLOCKED. no exit visible in viewport`. So the BFS itself never had
a path to walk; the FRAMES_PER_TILE timing wasn't even tested.

## Root cause for THIS run

The viewport is 16×16 around party. At localX=7, localY=6 the visible window
is roughly (-1, -2) to (14, 13). The actual exit (south edge of playable area)
is around localY=14-15 — **outside the visible window**. BFS sees walls and
floor but no DOOR/STAIRS/WARP/south-edge exit, so returns BLOCKED.

Earlier runs (V2.4.1 / V2.4.4) party was at `(5, 28)` — deep in the map, where
viewport DID see the south edge. Those runs failed on button-press timing
(now V2.4.5 frames=48), which we never got to verify here because the
pathfinder failed first.

## What V2.4.5 confirms

- Multi-map classifier (V2.4.2) handles mapId=0 (Coneria Castle, different from
  mapId=8 = Coneria town).
- Phase classifier identifies mapId=0 correctly.
- `findPathToExit` returns `BLOCKED` cleanly (with reason) when no exit visible —
  no crashes.
- ExitInterior reports `BLOCKED` immediately rather than thrashing.

## What V2.4.5 doesn't reveal

- Whether FRAMES_PER_TILE=48 actually moves the party indoors (the test never
  ran because pathfinder bailed first).
- Whether PostBattle dismissal works live (V2.4.3 fix still untested).
- Whether stairs A-tap works (no STAIRS reached).

## V2.4.6 fix (next session)

`InteriorPathfinder` should BFS the **full 64×64 InteriorMap**, not the 16×16
viewport. Recommended approach: add `InteriorMap.readFullMapView()` returning a
64×64 ViewportMap; ExitInterior passes that to pathfinder; advisor's ASCII
rendering stays at 16×16 to keep prompt size manageable.

Once V2.4.6 lands, this run's spawn position (7, 6) becomes solvable: BFS over
the full map will find the south-edge exit at localY~14-15, return a ~10-step
path, and ExitInterior (with frames=48 timing) walks it.

## Comparison

| Metric | V2.4.4 | V2.4.5 (this run) |
|---|---|---|
| FRAMES_PER_TILE | 24 | 48 |
| Spawn position | mapId=8, (5, 28) | mapId=0, (7, 6) |
| findPath returns path? | yes (17-step) | NO — BLOCKED |
| Tested timing fix live? | yes (failed) | NO — bailed before walking |
| New blocker? | timing too short | viewport too small |

## Files touched in V2.4.5

- `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt` — bumped
  FRAMES_PER_TILE 24 → 48. Untested live this run.
