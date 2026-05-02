# V2.1 long run — autonomous play, stuck inside Coneria castle

**Outcome:** `OutOfBudget` (skill cap or wall-clock cap, ~80 invocations / 14 min). Did NOT reach Garland.

## What worked

- Run dir: `~/.knes/runs/2026-05-02T09-58-58.398456Z` (default per Trace.kt).
- Full reasoning preserved in trace (advisor input + plan, executor input + result, untruncated).
- Title screen → overworld in turn 0 (1 outer turn, ~30s).
- Turn 1: walked from (146, 158) to (146, 152), 6 tiles north — partial success of `walkOverworldTo`.
- Turns 2-21: agent reasons clearly, identifies "terrain blocked", tries random walk, attempts east route. **All decisions reasonable.**
- New `~/.knes/runs/<timestamp>/` default run dir works (env `KNES_RUN_DIR` override available).

## What failed

- After reaching (146, 152), agent could not move ANY direction. `walkOverworldTo` returned ok=false repeatedly. `walkUntilEncounter` also failed to escape the tile.
- Most likely cause: (146, 152) is INSIDE Coneria castle, not on the overworld. FF1 RAM has `locationType` (0x000D): 0x00=outside, 0xD1=inside. Our V2 `RamObserver` doesn't read this field — we classify any non-battle non-zero-coords state as `Overworld`, but interior locations have their own coordinate system (`localX`/`localY` at 0x0029/0x002A).
- The agent's reasoning explicitly hypothesises this in turn 11: "likely stuck inside or at a boundary of Coneria castle".

## V2.2 follow-ups (next iteration)

1. **`Indoors` phase in FfPhase.** Read `locationType`; classify `0xD1` as `Indoors(localX, localY)`.
2. **`exitBuilding` skill.** From inside, walk south until `locationType == 0x00` (or until phase changes).
3. **A* pathfinding in `walkOverworldTo`.** Currently greedy; needs to know walkable tiles. Extract tile classification from ROM.
4. **CostTracker** (Phase 6 of V2 plan) — measure actual run cost.

## Trace highlights (full text in `trace.jsonl`)

```
turn  4: walked north 6 tiles (146,158→146,152). WalkOverworldTo target was (146,130).
turn  6: "Attempted to walk north ... made no progress after 50 steps. The terrain appears blocked."
turn  9: "Both greedy walk and random walk failed"
turn 11: "Party is stuck at (146, 152). ... likely stuck inside or at a boundary of Coneria castle."
turn 17: "water tiles block northward movement. The advisor suggests routing EAST around the terrain first."
turn 21: still at (146, 152), idle=7. Skill budget exhausted.
```

## Honest framing

V2 pipeline (singleRunStrategy + skill library + FF1-aware prompts + reasoning trace) is the right architecture. The agent autonomously plays the game and **decides correctly** — including hypothesising its own failure mode. The blocker is **information & skill library**: we don't expose interior/exterior distinction, and the navigation skill is greedy with no obstacle awareness. V2.2 fixes both.

