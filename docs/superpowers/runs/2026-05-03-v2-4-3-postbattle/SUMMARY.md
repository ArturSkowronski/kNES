# V2.4.3 evidence — PostBattle fix landed; ExitInterior frames bug surfaces

Run on 2026-05-03 ~05:26. Args: `--max-skill-invocations=40 --wall-clock-cap-seconds=720`.
Trace: `2026-05-03T05-26-29.573250Z/trace.jsonl` (21 events).

## Headline

**PostBattle fix is in but didn't get exercised this run.** The LLM advisor
this time chose to walk straight north into Coneria town (entered at (145,
152) → `Indoors(mapId=8, localX=5, localY=28)`). `exitInterior` then **failed
in a brand-new way**: `findPathToExit` correctly identifies a 17-step path to
exit at (10, 32), but `exitInterior` reports stuck at (5, 28) for 17
consecutive turns. Party never physically moves.

## Root cause (identified, fixed in V2.4.4)

`ExitInterior.FRAMES_PER_TILE = 16` — too short for FF1 indoor walking. The
button press doesn't last long enough to consume one tile of party motion.
Loop pattern:

1. Read RAM: localX=5, localY=28.
2. Pathfinder: "go EAST".
3. Press EAST for 16 frames.
4. Read RAM again: localX=5, localY=28 (party didn't actually move).
5. Idle-detection fires: `fog.markBlocked(6, 28)`.
6. Repeat — pathfinder now avoids (6, 28), tries another direction. Same fail.
7. After ~10 iterations, all neighbours marked blocked → BFS returns no-path.

V2.3 hit the exact same problem on the overworld and bumped
`WalkOverworldTo.FRAMES_PER_TILE` from 16 to 24. We forgot to mirror that fix
in `ExitInterior` when V2.4 introduced it. **V2.4.4 commit applies the bump.**

## What actually got tested in V2.4.3

The `BattleFightAll.canExecute` widening (now accepts both BATTLE 0x68 and
POST_BATTLE 0x63) and the iterative A-tap loop with 30-frame waits were
committed. Tests pass. But the agent never reached battle in this run, so
the PostBattle path wasn't exercised live. Will be exercised in V2.4.4 once
the frames fix lets the agent escape Coneria.

## Comparison

| Metric | V2.4.2 | V2.4.3 (this run) |
|---|---|---|
| Multi-map classifier coverage | yes | yes |
| Reaches battle? | yes | NO — stuck Indoors first |
| PostBattle modal dismissal | not tested | not tested (no battle) |
| Reaches overworld after Indoors? | n/a | NO |
| ExitInterior actually moves party? | n/a | **NO — FRAMES_PER_TILE=16 bug** |

## Files touched in V2.4.3

- `knes-debug/src/main/kotlin/knes/debug/actions/ff1/BattleFightAll.kt`
  — extended `canExecute` for PostBattle, replaced 10-tap flush with
  iterative loop.
