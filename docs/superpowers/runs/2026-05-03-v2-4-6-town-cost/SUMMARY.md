# V2.4.6 evidence — town cost-weighting nigdy nie odpalone; spawn mis-classified as Indoors

Run 2026-05-03 ~07:03. Args: `--max-skill-invocations=40 --wall-clock-cap-seconds=720`.
Trace: `2026-05-03T07-03-15.752373Z/trace.jsonl`. Outcome: `OutOfBudget` (12m wall).

## Headline

V2.4.6 patch (Dijkstra + TileType.cost: TOWN/CASTLE=50 transit / 1 destination)
shipped clean and unit-tested, but **the live run never exercised it**. Agent
spawned in `Indoors(mapId=8, localX=5, localY=28)` immediately after
`pressStartUntilOverworld` and looped on `exitInterior` (returns "did not exit
in 64 steps") + `askAdvisor` until budget ran out. Same spawn signature as
V2.4.4. Path B (overworld town avoidance) is correct in isolation but solves
a problem we never reach.

## Root cause for THIS run

Phase classifier (`RamObserver.classifyPhase`, V2.3.1 heuristic at lines 63-66)
treats *any* non-zero `localX/localY` as Indoors regardless of `locationType`:

```kotlin
// V2.3.1: locationType==0xD1 is castle/dungeon interior. Town outdoor maps
// (e.g. Coneria) have locationType==0 but populate localX/localY anyway.
if (partyCreated && (
        (ram["locationType"] ?: 0) == LOCATION_TYPE_INDOORS ||
        onLocalMap  // localX != 0 || localY != 0
    )) return FfPhase.Indoors(...)
```

Spawn state observed in this run:

```
locationType=0, currentMapId=8, worldX=145, worldY=152,
localX=5, localY=28, bootFlag=77, char1_hpLow=35
```

`locationType=0` says overworld. But `localX/localY` are non-zero (residual /
spawn-routine state), and `currentMapId=8` is also residual (not overworld
sentinel). Heuristic fires → Indoors(mapId=8, 5, 28) → ExitInterior loops
inside a map the party isn't actually in.

Earlier we assumed V2.4.4's `Indoors(mapId=8, 5, 28)` was the agent walking
*into* Coneria. It wasn't — it's the spawn state being mis-classified.
Same in V2.4.5 with `mapId=0, (7,6)` (different residual values, same root).
Two iterations of "fix the exit" were chasing a perception bug, not a
movement bug.

## What V2.4.6 confirms

- `:knes-agent:test` green incl. 3 new cost-weighting tests.
- Build pipeline still works.
- Live spawn classification is the actual blocker (not pathfinder cost
  policy, not ExitInterior viewport size, not FRAMES_PER_TILE timing).

## What V2.4.6 does NOT confirm

- Whether town cost-weighting changes overworld navigation (never reached).
- Whether intent-aware destination cost works live (never reached).
- Anything about ExitInterior, PostBattle, stairs A-tap.

## Real fix candidates (V2.4.7)

**A) Tighten phase classifier — prefer locationType.**
If `locationType == 0` AND `worldX/Y` look valid (>0 and within overworld
bounds), classify as Overworld even when `localX/Y` are non-zero. Drops the
"Coneria-town-outdoor" V2.3.1 carve-out and replaces with explicit detection
of that case (e.g. specific `currentMapId` set or specific `worldX/Y` tile
classified as TOWN). Risk: re-breaks Coneria town outdoor walking.

**B) Bootstrap-clear RAM after pressStartUntilOverworld.**
After intro, walk a single frame N/S to force the engine to re-init
locationType/localX/localY/currentMapId to canonical overworld values. Hack
but localized.

**C) Inspect what RAM actually looks like for the *real* "Coneria town
outdoor" state** vs spawn. The V2.3.1 carve-out was added based on assumed
RAM signatures. If those signatures were inferred not measured, they may be
wrong. A 5-minute capture (walk into Coneria from overworld, dump RAM) would
disambiguate. Best ROI before picking A or B.

## Recommendation

C first (cheap evidence), then A. A is the right structural fix; B is a
hack. V2.4.6 patch (Dijkstra cost) stays in — it's correct, and once
classification is fixed it'll do its job.

## Files

- `knes-agent/.../ViewportPathfinder.kt` — Dijkstra (kept)
- `knes-agent/.../perception/TileType.kt` — `cost()` (kept)
- `knes-agent/.../perception/RamObserver.kt:55-72` — classification heuristic
  to reconsider in V2.4.7

Commit: `340a766 feat(agent): V2.4.6 — intent-aware TOWN/CASTLE cost weighting on overworld`.
