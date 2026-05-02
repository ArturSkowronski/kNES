# FF1 Koog Agent V2.3 — Deterministic Pathfinding + Viewport Map + Fog-of-War

**Status:** design accepted 2026-05-02, pending implementation plan.
**Builds on:** V2.2 (Indoors phase scaffold, branch `ff1-agent-v2` HEAD `29aa653`).
**Driven by:** evidence from PR #96 / #97 — agent reaches Overworld and walks, but
gets blocked at world coord (146, 152) and exhausts budget. Greedy `walkOverworldTo`
has no obstacle awareness. Pathfinding research informed by Gemini Plays Pokemon
(Joel Z, May–June 2025; see `docs/superpowers/research/2026-05-01-llm-game-agents.md`).

## Goal

Eliminate the "blocked-by-terrain" navigation deadend on the FF1 overworld by
adding (1) a deterministic 16×16 viewport pathfinder exposed as a tool, (2) a
fog-of-war accumulator, and (3) ASCII map rendering for advisor input — without
changing the executor's overall control flow.

## Non-goals (V2.3)

- Cross-run fog persistence (V2.4)
- Full overworld map parsing from ROM (V2.5)
- A\* with cost weights, e.g. avoid encounter-heavy forests (V2.6)
- LLM-based pathfinder sub-agent for puzzles (V2.7)
- Tile classification for town/castle interiors (companion to V2.2 Indoors)
- Screenshot input to advisor (deferred until ASCII proves insufficient)
- CostTracker (V2 plan Phase 6, separate work)

## Architecture

```
knes-agent/
  perception/
    NametableReader.kt      [NEW]  reads PPU $2000-$2FFF, returns 16x16 tile IDs
                                   centered on party
    TileClassifier.kt       [NEW]  Int tileId -> TileType
    ViewportMap.kt          [NEW]  16x16 grid of TileType + party-relative origin
    FogOfWar.kt             [NEW]  Map<(worldX, worldY), TileType> + blockedSet,
                                   per-run only
    RamObserver.kt          [edit] also produces ViewportMap each turn
  pathfinding/              [NEW]
    PathResult.kt           data class
    SearchSpace.kt          enum (V2.3 only emits VIEWPORT)
    ViewportPathfinder.kt   BFS over ViewportMap; deterministic
    Pathfinder.kt           facade interface
  skills/
    SkillRegistry.kt        [edit] adds @Tool findPath(targetX, targetY)
    WalkOverworldTo.kt      [edit] internally calls findPath; if found ->
                                   executes step sequence; if not -> returns
                                   blocked + viewport snapshot
  advisor/
    AdvisorAgent.kt         [edit] prompt now includes ASCII viewport, fog stats,
                                   blocked-tiles list
  runtime/
    AgentSession.kt         [edit] wires FogOfWar lifecycle (clear on start,
                                   merge each turn from ViewportMap)
```

### Boundaries

- `Pathfinder` is a pure function: input (from, to, viewport, fog) -> PathResult.
  No LLM. No mutable state.
- `FogOfWar` is process-local, in-memory, cleared on session start.
- `TileClassifier` is a pure function `Int -> TileType`, configured by a JSON
  classification table loaded once.
- The LLM never receives raw button-press tools for overworld navigation — it
  goes through `findPath` and `walkOverworldTo`.

## Data flow per turn

```
1. RamObserver.observe()
     - reads RAM (existing markers: worldX/Y, char_status, screenState, hp, ...)
     - reads PPU nametable -> NametableReader.readViewport()
     - TileClassifier.classifyAll(tileIds) -> ViewportMap
     - returns Observation(phase, ramSnapshot, viewportMap)

2. FogOfWar.merge(observation.viewportMap, partyWorldXY)
     - for each tile in viewport: store (worldX+dx, worldY+dy) -> TileType
       (latest seen wins)

3. SuccessCriteria.evaluate(observation) -> Outcome (unchanged)

4. Phase router:
     - non-Overworld phases: unchanged (executor + existing skills)
     - Overworld:
         build prompt = RAM ground-truth + ASCII viewport + fog stats +
                        blocked tiles + goal stack
         ExecutorAgent.run(prompt) (Sonnet 4.5)
           - may call findPath(targetX, targetY)         -- deterministic
           - may call walkOverworldTo(x, y)              -- uses findPath
           - may call askAdvisor(reason)                 -- gets ASCII + fog
           - may call other skills (battleFightAll, getState, ...)

5. Trace.append(turnRecord)  (full input/output, no truncation)

6. Budget check (cost / wall-clock / skill-invocations) -> continue or halt
```

## TileClassifier

Empirical mapping of overworld tile IDs (from PPU nametable) to terrain type.
Built by:

1. **Research test** (`TileClassifierResearchTest`): boots ROM, advances to
   known overworld positions (spawn Coneria, eastern beach, northern forest,
   bridge to Pravoka, mountain edge, Coneria town entrance), dumps the 16×16
   hex grid for each, saves screenshots.
2. **Manual classification**: human compares hex IDs to screenshots, fills
   `knes-agent/src/main/resources/tile-classifications/ff1-overworld.json`.
3. **Code**: `TileClassifier(table)` does `Int -> TileType` lookup.

```json
{
  "version": 1,
  "rom": "ff1-us-rev-a",
  "byType": {
    "GRASS":    [<ids>],
    "FOREST":   [<ids>],
    "MOUNTAIN": [<ids>],
    "WATER":    [<ids>],
    "BRIDGE":   [<ids>],
    "ROAD":     [<ids>],
    "TOWN":     [<ids>],
    "CASTLE":   [<ids>]
  },
  "default": "UNKNOWN"
}
```

`isPassable`:

| TileType   | Passable |
|------------|----------|
| GRASS, FOREST, ROAD, BRIDGE | yes |
| TOWN, CASTLE | yes (entry transitions to Indoors phase) |
| MOUNTAIN, WATER | no |
| UNKNOWN | no (conservative: prefer blocked over wandering) |

## Pathfinder

```kotlin
data class PathResult(
    val found: Boolean,
    val steps: List<Direction>,           // empty if !found
    val reachedTile: Pair<Int, Int>,      // world coords of last tile in path
    val searchSpace: SearchSpace,         // VIEWPORT in V2.3
    val partial: Boolean,                 // true if reachedTile != requested target
    val reason: String? = null            // "target outside viewport", "no path"
)

enum class Direction { N, S, E, W }       // FF1 overworld is 4-way
enum class SearchSpace { VIEWPORT, FOG, FULL_MAP }

interface Pathfinder {
    fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        viewport: ViewportMap,
        fog: FogOfWar
    ): PathResult
}

class ViewportPathfinder : Pathfinder { /* BFS over 16x16 */ }
```

Key behavior:

- **Standard BFS** over 16×16 grid; queue + visited boolean array + parent map.
- Skips tiles where `!classifier.isPassable(viewport[x][y])` OR tile in
  `fog.blockedSet`.
- **Target outside viewport** -> partial path to closest in-viewport tile in
  target direction (still BFS-found, not pure greedy).
- **Path length cap = 32 steps** (max sensible in 16×16 with detours);
  truncate + partial=true if exceeded.

## Tool exposure

```kotlin
@Tool(
  "Find walkable path from current position to target world coordinates within "
+ "visible 16x16 viewport. Returns step sequence or blocked. Deterministic; "
+ "consumes no LLM tokens."
)
fun findPath(targetX: Int, targetY: Int): String {
    val obs = ramObserver.lastObservation()
    val result = pathfinder.findPath(
        from = obs.partyXY, to = targetX to targetY,
        viewport = obs.viewportMap, fog = fog
    )
    return when {
        result.found && !result.partial ->
          "PATH ${result.steps.size} steps: ${result.steps.joinToString(",")}"
        result.found && result.partial ->
          "PARTIAL ${result.steps.size} steps to ${result.reachedTile}; "
        + "target outside viewport. Walk this path then call findPath again."
        else -> "BLOCKED. ${result.reason}. Suggest askAdvisor."
    }
}
```

`walkOverworldTo` becomes a thin shim: calls `findPath`, executes the returned
sequence button-by-button, marks any non-moving step's target tile as
`fog.blocked`, returns viewport snapshot on failure.

## ASCII rendering for advisor

```
WORLD VIEW (party at world coord 146,152; viewport 16x16):

     138 140 142 144 146 148 150 152 154
  144  ^   ^   ^   ^   ^   .   .   .   .
  146  ^   ^   ^   .   .   .   .   .   .
  148  ^   .   .   .   .   .   .   .   .
  150  ~   .   .   .   .   .   .   T   .
  152  ~   ~   .   .   @   .   .   .   .
  154  ~   ~   ~   .   .   .   .   .   .
  156  ~   ~   ~   ~   .   .   .   .   .
  158  ~   ~   ~   ~   ~   .   .   .   .

Legend: @ party, . grass, ^ mountain, ~ water, F forest,
        R road, B bridge, T town, C castle, ? unseen, X blocked-confirmed

FOG STATS: 134 tiles visited, bbox (138-160, 144-162).
RECENTLY BLOCKED: (146,151), (147,151) — tried walking N, no movement.
```

V2.3 renders only viewport; out-of-viewport coords show as `?`. V2.4 adds
fog-aware rendering for tiles previously seen.

## Advisor prompt change (D' from brainstorm)

`AdvisorAgent` system prompt now includes:

1. ASCII map (above)
2. RAM ground-truth (HP, gold, party levels) — already present
3. Goal stack — already present
4. Reason executor invoked advisor — already present
5. **No screenshot in V2.3** (Gemini-PP finding: textual tile grid >= raw pixels;
   start cheaper, add screenshot in V2.4 if advisor demonstrably stuck on
   spatial reasoning).

## Error handling

| Failure mode | Handling |
|---|---|
| Tile classification table missing / parse error | Fallback all-UNKNOWN -> all-impassable -> agent stuck -> outOfBudget. WARN at session start. |
| Nametable read fails (PPU not initialized) | Viewport = null -> findPath returns blocked("viewport unavailable"); executor falls back to legacy walk |
| BFS exceeds 32 steps | Truncate, partial=true, reason="path too long" |
| Target far outside viewport (>20 tiles) | Skip BFS; return partial=true with greedy direction-only suggestion |
| FogOfWar grows large | No-op; render to advisor as viewport-only + bbox stats |

## Testing

**Unit (`knes-agent-tests`):**

- `ViewportPathfinderTest` — direct path, L-shape detour, fully blocked,
  target outside viewport, target == origin, fog blocks override classifier,
  dense maze.
- `TileClassifierTest` — known IDs, unknown -> UNKNOWN, JSON parse fallback.
- `FogOfWarTest` — merge add, merge overwrite, clear, blockedTile persists.
- `NametableReaderTest` — center extraction, edge handling.

**Integration (gated by ROM presence):**

- `PathfinderViewportLiveTest` — boot to overworld, assert findPath returns
  expected sequence; move party, assert path shortens; approach mountain edge,
  assert blocked.
- `OverworldNavigationE2ETest` — boot, run agent ≤30 turns, assert party
  manhattan-displaces ≥10 from spawn AND no tile visited > 5 times.

**Golden file:**

- `TileClassificationGoldenTest` — 6 saved overworld snapshots; render ASCII;
  diff against committed golden `.txt` files. Update via
  `-PupdateGolden`.

## PR strategy

- **PR #98**: V2.2 standalone — `feat(agent): V2.2 — Indoors phase scaffold
  (does not fix V2.1 deadend; valid for future town/castle entry)`. Body
  preserves the honest narrative that the V2.2 hypothesis was wrong.
- **PR #99**: V2.3 on top of V2.2 — `feat(agent): V2.3 — deterministic
  findPath + viewport map + fog-of-war`. Includes evidence run.

## Definition of done

1. All unit tests pass.
2. `OverworldNavigationE2ETest` shows party escapes (146, 158) deadend
   (manhattan displacement ≥ 10 within 30 turns).
3. ASCII grid in `trace.jsonl` shows sensible terrain glyphs on overworld.
4. Trace shows LLM invokes `findPath` -> deterministic response -> step
   execution.
5. CI green.
6. PR #99 description includes before/after run evidence (consistent with
   PR #96 / #97 format).

## Future evolution (interface stable)

`findPath` API does not change across versions; only `searchSpace` widens.

| Version | Search space          | Trigger                                  |
|---------|-----------------------|------------------------------------------|
| V2.3    | viewport 16×16        | now                                      |
| V2.4    | viewport + fog accumulator | V2.3 insufficient to reach Garland   |
| V2.5    | full overworld map (ROM RLE) | long inter-town routes needed       |
| V2.6    | A\* with cost weights | strategic encounter avoidance            |
| V2.7    | LLM-pathfinder sub-agent | Sokoban / puzzle dungeons             |
