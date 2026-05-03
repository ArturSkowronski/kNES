# V2.4.4 evidence — frames=24 didn't unblock indoor walk

Run on 2026-05-03 ~05:40. Args: `--max-skill-invocations=40 --wall-clock-cap-seconds=720`.
Trace: `2026-05-03T05-40-10.780384Z/trace.jsonl` (20 events).

## Headline

**The hypothesis was wrong.** Bumping `ExitInterior.FRAMES_PER_TILE` from 16 to
24 (matching the overworld value) did NOT unblock indoor walking. Party reached
`Indoors(mapId=8, localX=5, localY=28)` and stayed there for 15+ turns despite
`findPathToExit` returning a valid 17-step path on every query.

## Phase progression

```
TitleOrMenu
  → Overworld(146, 158)
  → Overworld(146, 152)   walked north (overworld pathfinder works)
  → Indoors(mapId=8, localX=5, localY=28)
  → … 15 turns of stuck …
```

## What's confirmed working

- Overworld navigation via WalkOverworldTo (frames=24): party did move from
  (146, 158) to (146, 152) on overworld.
- Phase classifier on entry to Coneria.
- `findPathToExit` consistently identifies a 17-step path to exit at (10, 32).

## What's still broken

ExitInterior loop's button presses do not move the party indoors. Hypotheses:

1. **24 frames still too short for indoor tile motion** — FF1 interior walk
   animation may be slower than overworld. Bump higher (48? 60?).
2. **Indoor walking requires button hold, not per-tile tap** — `toolset.step`
   sets buttons, advances frames, then releases (between calls). Indoor engine
   may need continuous press across multiple tiles.
3. **InputQueue routing differs indoors** — emulator state inside town/castle
   maps may not pump frame inputs through the same path as overworld.

V2.4.5 tries hypothesis (1) with FRAMES_PER_TILE=48. If that also fails, the
issue is structural and we'll need a fundamentally different input pattern.

## Comparison

| Metric | V2.4.3 | V2.4.4 (this run) |
|---|---|---|
| FRAMES_PER_TILE indoors | 16 | 24 |
| Party physically moves indoors? | no | NO |
| ExitInterior reports stuck at (5,28)? | yes (17 turns) | yes (15 turns) |

## Files touched in V2.4.4

- `knes-agent/src/main/kotlin/knes/agent/skills/ExitInterior.kt` — bumped
  FRAMES_PER_TILE 16 → 24 (didn't help; V2.4.5 tries 48).
