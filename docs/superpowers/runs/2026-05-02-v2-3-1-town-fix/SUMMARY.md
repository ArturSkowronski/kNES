# V2.3.1 evidence — Indoors phase fix unlocks deeper gameplay

Run on 2026-05-02 ~14:30. Args: `--max-skill-invocations=25 --wall-clock-cap-seconds=540`.
Trace: `2026-05-02T14-30-51.949786Z/trace.jsonl` (16 events).

## Headline

**Phase classifier fix works.** After party enters Coneria, V2.3.1 correctly
classifies the state as `Indoors(localX=5, localY=29)` and the executor calls
`exitBuilding`. Inside the castle the agent fought 5 random encounters with
`battleFightAll`, advancing localY 29→17 (12 tiles of progress on the castle
interior map).

## Outcome

- Phase transitions: `TitleOrMenu` → `Overworld(146, 158)` → `Indoors(localX=5, localY=29)` → 5× `Battle/PostBattle` cycles → `Indoors(localX=9, localY=17)`.
- `exitBuilding` partially works (south-walking triggered combat which is canonical FF1 castle behaviour) but doesn't successfully exit; party gets stuck at localY=17.
- **OutOfBudget** after 25 skills.

## Tools used

- `pressStartUntilOverworld` × 1
- `exitBuilding` × ~4 (partial progress per call due to encounter interruptions)
- `battleFightAll` × 5
- `walkOverworldTo` × 2 (failed — wrong phase)
- `findPath` × 2 (returned BLOCKED — viewport built from world coords but party on local map)
- `askAdvisor` × 2

## Why exitBuilding doesn't fully escape

Walking SOUTH inside Coneria castle is correct FF1 convention but the path is
not a straight line — castle interior has stairs / doors that re-orient the
party (RAM resets/teleports localX/Y between maps). Each battle additionally
returns party to a "battle return point" which may not preserve south-walk
progress. The skill needs awareness of castle multi-floor structure (V2.4).

## Comparison to V2.3 raw

| Metric | V2.3 raw | V2.3.1 (this run) |
|---|---|---|
| Reaches Coneria entrance | yes (1 turn) | yes (1 turn) |
| Recognises Indoors phase? | no — stuck classifying as Overworld | YES |
| Calls exitBuilding? | no — phase wrong | yes — multiple times |
| Combat triggered? | no | 5 battles, all won |
| Escapes castle? | n/a | no (stairs/structure unhandled) |

## Next bug to fix (V2.4 scope)

`exitBuilding` is too simple. Real FF1 castle exit requires:
1. Detect when party teleports between sub-maps (localX/Y discontinuities).
2. Decode FF1 castle interior tile layout from ROM (similar to how V2.3 decoded
   overworld) — find the door/stairs tile that leads OUT.
3. Or: combine with pathfinder over castle local map.

For now, V2.3.1 is the correct landing point: phase classification works, agent
engages with game phases correctly, blocked only by structural complexity of
FF1 interior maps.
