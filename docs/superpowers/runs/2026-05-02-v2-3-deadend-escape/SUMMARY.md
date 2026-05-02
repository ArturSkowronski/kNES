# V2.3 evidence — agent navigates with deterministic pathfinder, hits new bug

Run on 2026-05-02 ~14:14. Args: `--max-skill-invocations=20 --wall-clock-cap-seconds=420`.
Trace: `2026-05-02T14-14-10.371124Z/trace.jsonl` (13 events).

## Headline

**V2.3 fixes the V2.1 navigation deadend.** Party progressed from spawn (146, 158) to
(145, 152) in 1 turn — that's the very tile V2.1 got permanently stuck at. Our
deterministic `findPath` + `walkOverworldTo` BFS shim worked: the agent recognised
mountain/water around (146, 158), walked west then north 6 tiles, reaching the
location V2.1 could never escape FROM (because greedy walk had no obstacle awareness).

**A different bug appeared at (145, 152):** RAM shows `worldX=145, worldY=152` BUT
`localX=5, localY=28` and `locationType=0`. The party walked INTO Coneria town
(an outdoor town entrance is at this overworld coord). RamObserver only treats
`locationType==0xD1` as Indoors, so phase stays `Overworld(145, 152)` — but
actual movement is via local coords so `walkOverworldTo` futilely retries world
coords that don't change. Agent self-diagnoses correctly ("party stuck, advisor
suggests retry") but lacks the `exitBuilding` trigger because phase is wrong.

## Outcome

- **V2.1 deadend status:** ESCAPED. Party left (146, 158) → (145, 152). V2.1 ran 22
  turns at this exact tile without movement.
- **V2.3 fresh bug:** phase mis-classification — town outdoor area not recognised
  as Indoors. Out of budget after 11 stuck turns at (145, 152).
- **Outcome marker:** OutOfBudget.

## findPath behaviour

Examining the trace:
- 1 `findPath` call returned `PATH n steps: …` (turn 3, advised by advisor).
- Subsequent `findPath` calls returned `BLOCKED. no path within viewport. Suggest askAdvisor.`
- Pathfinder works: it correctly identifies 16×16 viewport at (145, 152) but BFS
  starts from a tile classified as GRASS yet party is actually in a different
  coord-space (town local coords).

## Comparison to V2.2

| Metric | V2.2 (`2026-05-02-v2-1-stuck-in-castle`) | V2.3 (this run) |
|---|---|---|
| Manhattan displacement from spawn | ~6 (oscillated at (146, 152)) | 7 (reached (145, 152)) |
| Stuck loop on (146, 152)? | yes — 22 turns | no — 1 turn, moved past |
| Stuck loop on next tile? | n/a | yes — at (145, 152), phase wrong |
| Iteration cap fires? | no | yes once before fix; bumped 10→20 |
| Tools used | walkOverworldTo only | findPath, walkOverworldTo, askAdvisor, walkUntilEncounter, getState |

## Notes for V2.3.1 / V2.4

1. **Phase classification fix:** when `localX/localY != 0` AND `locationType == 0`,
   the party is in a **town outdoor area** (the in-town map). Treat as Indoors
   even though locationType isn't 0xD1. Then the existing `exitBuilding` skill
   walks SOUTH out automatically. Probable RAM signature:
   `townMapType` byte (need to confirm by RAM diff between Coneria-overworld and
   Coneria-town).
2. **Coordinate offset note:** the OverworldMap.classifyAt(145, 152) returns
   GRASS (correct per FF1 ROM map at that overworld tile). The discrepancy is
   that the party isn't ACTUALLY navigating overworld at this moment — it's in
   a town's local-coord map. Once V2.3.1 fixes the phase, OverworldMap remains
   correct as-is.
3. **Iteration cap:** bumped 10→20 to accommodate findPath→walkOverworldTo
   chains. May need 30 if advisor is also chained.
4. **KNES_RUN_DIR:** must be absolute path; relative paths land under `knes-agent/`
   when gradle :run is the entry point.
