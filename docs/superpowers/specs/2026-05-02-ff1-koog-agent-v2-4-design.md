# FF1 Koog Agent V2.4 — Interior Map Decoder + Exit Pathfinder

**Status:** design accepted 2026-05-02, pending implementation plan.
**Builds on:** V2.3.1 (PR #98 merged to master).
**Driven by:** evidence from V2.3.1 live run — agent recognises Coneria castle as
`Indoors`, calls `exitBuilding`, but the greedy walk-SOUTH skill cannot escape
multi-segment castle interiors. Party fights 5 random encounters and gets stuck
inside.

## Goal

Enable the agent to exit any FF1 castle / town / dungeon interior by decoding the
current interior map from ROM, classifying tile types, and pathfinding to the
nearest exit (door / stairs / warp). Sequential strategy: solve one map at a
time; rely on the outer loop to detect sub-map transitions and re-pathfind.

## Non-goals (V2.4)

- Graph pathfinding across sub-maps (V2.5).
- Treasure-chest interaction, NPC dialogue, shopping (V2.6+).
- Combat strategy beyond `battleFightAll` (V2.X).
- Cross-run fog persistence (deferred from V2.3 non-goals; still deferred).

## Architecture

```
knes-agent/
  perception/
    InteriorMap.kt            [NEW]  decoded byte grid for one map
    InteriorMapLoader.kt      [NEW]  parses banks 4-7 RLE; lazy single-map decode
    InteriorTileClassifier.kt [NEW]  byte -> TileType (extended with DOOR / STAIRS / WARP)
    MapSession.kt             [NEW]  caches current map + provides ViewportSource;
                                     reloads on currentMapId change
    RamObserver.kt            [edit] reads currentMapId byte; FfPhase.Indoors carries it
    TileType.kt               [edit] add DOOR (D), STAIRS (>), WARP (*) values
  pathfinding/
    InteriorPathfinder.kt     [NEW]  BFS-to-nearest-exit; reuses Pathfinder interface
    SearchSpace.kt            [edit] add LOCAL_MAP value
  skills/
    SkillRegistry.kt          [edit] new @Tool findPathToExit(); exitInterior(maxSteps)
    ExitBuilding.kt           [edit] alias / shim that delegates to exitInterior
  runtime/
    AgentSession.kt           [edit] threads MapSession through observer + skills
```

### Boundaries

- `InteriorMapLoader` is a pure function `(rom: ByteArray, mapId: Int) -> InteriorMap`.
- `MapSession` is the only mutable layer: caches map by id, swaps on transition.
- `InteriorPathfinder` is pure; same `Pathfinder` interface as `ViewportPathfinder`.
- `TileType` extension is additive; existing overworld classifier doesn't emit
  the new values, so it doesn't break.

## Data flow per turn

```
1. RamObserver.observeFull()
     - reads currentMapId from RAM (byte address TBD - see Risks)
     - phase = Indoors(mapId, localX, localY) when partyCreated AND
       (locationType == 0xD1 OR (localX != 0 OR localY != 0))

2. MapSession.ensureCurrent(mapId)
     - if cached id == mapId: return cached InteriorMap
     - else: InteriorMapLoader.load(rom, mapId), cache, fog.clear()

3. AgentSession.runTurn():
     - phase=Indoors -> executor sees ASCII interior view (AsciiMapRenderer
       reused; new glyphs D / > / *)
     - executor likely calls exitInterior

4. exitInterior tool (deterministic):
     loop until maxSteps OR onOverworld OR encounter:
       - if mapId changed since last iter: ensureCurrent(mapId), continue
       - viewport = mapSession.readViewport(localX to localY)
       - fog.merge(viewport)
       - path = pathfinder.findPath(localXY, /*unused*/, viewport, fog)
       - if not found: return blocked
       - press buttons; if step lands on STAIRS / WARP, tap A; mark idle moves
         as fog.blocked

5. After exit -> phase=Overworld -> V2.3 path takes over
```

## Interior map format

From the FF1 wiki:

- Pointer table at start of bank 4 (file offset `0x10010`).
- Each pointer is 16-bit; **upper 2 bits = bank index 0..3 (= bank 4..7)**;
  lower 14 bits = CPU address (starting at `0x8000` when the bank is mapped at
  `$8000`).
- Map data: same RLE as overworld but row width = 64 tiles, NO `0xFF` per-row
  terminator; one `0xFF` ends entire map. 2-byte entries can wrap rows.

```kotlin
class InteriorMapLoader(private val rom: ByteArray) {
    fun load(mapId: Int): InteriorMap {
        require(mapId in 0..127) { "mapId $mapId out of range" }
        val ptrFile = 0x10010 + mapId * 2
        val raw = (rom[ptrFile].toInt() and 0xFF) or
                  ((rom[ptrFile + 1].toInt() and 0xFF) shl 8)
        val bankIndex = (raw ushr 14) and 0x03
        val bank = 4 + bankIndex
        val cpuAddr = 0x8000 + (raw and 0x3FFF)
        val dataStart = bank * 0x4000 + 0x10 + (cpuAddr - 0x8000)
        return decodeRle(rom, dataStart)
    }
}
```

`decodeRle` streams bytes until `0xFF` total terminator. Emits row-major; rows are
fixed 64 wide. Final grid: 64×64 (max for FF1 interiors); shorter maps tail-pad
with 0x00.

## Tile classification

`InteriorTileClassifier` is a hardcoded byte-to-TileType lookup, built empirically
from a research test that decodes Coneria castle ground floor + Coneria town + a
representative dungeon (e.g. Marsh Cave entrance). Glyph mapping:

| Tile semantic                | TileType   | Glyph |
|------------------------------|------------|-------|
| Floor (walkable)             | GRASS      | `.`   |
| Wall (impassable)            | MOUNTAIN   | `^`   |
| Door (exits to overworld)    | DOOR       | `D`   |
| Stairs up/down (sub-map xfer)| STAIRS     | `>`   |
| Warp tile                    | WARP       | `*`   |
| Treasure chest               | UNKNOWN    | `?`   |
| Water/lava (impassable)      | WATER      | `~`   |
| NPC sprite tile              | UNKNOWN    | `?`   |

Reusing `GRASS`/`MOUNTAIN`/`WATER` from V2.3 keeps the renderer simple. Treasure
and NPC are passable-but-not-targets in real FF1 but classified UNKNOWN here so
BFS doesn't path through them (V2.4 doesn't open chests; it just routes around).

## InteriorPathfinder — BFS to nearest exit

```kotlin
class InteriorPathfinder(private val maxSteps: Int = 64) : Pathfinder {
    override fun findPath(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,    // ignored — goal is "any exit tile"
        viewport: ViewportMap,
        fog: FogOfWar,
    ): PathResult {
        // BFS, goal predicate = isExit(tile).
        // Returns PathResult with searchSpace = LOCAL_MAP.
    }
    private fun isExit(t: TileType): Boolean =
        t == TileType.DOOR || t == TileType.STAIRS || t == TileType.WARP
}
```

- BFS expands all passable tiles from party position.
- First visited exit-class tile wins (FIFO -> nearest by step count).
- No exit in viewport -> partial path toward viewport edge in best-explore direction.
- Fog blocked tiles override classifier (same rule as ViewportPathfinder).

## exitInterior skill

Pseudocode in design discussion (see brainstorm transcript). Key elements:

- Uses `MapSession` to refresh current map on `currentMapId` change.
- Watches `screenState == 0x68` (battle) to bail out cleanly.
- Watches `(locationType==0 AND localX==0 AND localY==0)` for "back on overworld" success.
- For STAIRS / WARP: when party stands on the target tile, tap A (FF1 convention).
- For DOOR: walking onto south edge of building auto-transitions; no A press.
- Idle-step detection: if `(mapId, localX, localY)` unchanged after a step, mark
  the target tile as fog-blocked and let pathfinder route around.

`ExitBuilding` becomes a thin alias that delegates to `exitInterior` so existing
callers (advisor prompts, tests) keep working.

## Tool exposure

```kotlin
@Tool("Find a walkable path from current local position to the nearest exit " +
      "(DOOR / STAIRS / WARP) in the current interior map. Returns step sequence. " +
      "Deterministic; consumes no LLM tokens. Use when phase is Indoors.")
fun findPathToExit(): String

@Tool("Walk to the nearest exit of the current interior map. Stops when " +
      "currentMapId changes (transitioned to next sub-map or back to overworld) " +
      "OR encounter starts. Marks blocked tiles. Bounded by maxSteps.")
suspend fun exitInterior(maxSteps: Int = 64): SkillResult
```

## Error handling

| Failure | Handling |
|---|---|
| `currentMapId` byte not yet identified | RamObserver returns `Indoors(mapId=-1, ...)`; MapSession returns degraded all-UNKNOWN map; InteriorPathfinder returns blocked; advisor called |
| Map decoder hits EOF before 0xFF | Pad rest with 0x00 (UNKNOWN); WARN |
| Bank pointer resolves to invalid file offset | Throw at load; caught upstream, falls back to legacy walkSouth `exitBuilding` |
| `InteriorTileClassifier` doesn't know a tile id | UNKNOWN -> impassable -> conservative |
| `exitInterior` runs maxSteps without progress | Returns ok=false with `(mapId, localX, localY)`; advisor decides |

## Testing

**Unit (zero-ROM):**
- `InteriorMapLoaderTest` — synthetic ROM bytes; decode small RLE map; verify
  dimensions, tile values, terminator handling, cross-row 2-byte wrap.
- `InteriorPathfinderTest` — direct path to single DOOR; nearest-among-multiple;
  through floor avoiding walls; no exit found; exit outside viewport.
- `InteriorTileClassifierTest` — known IDs map correctly; unknown -> UNKNOWN.
- `MapSessionTest` — cache hit on same id; invalidation + fog clear on id change.

**Integration (ROM-gated):**
- `InteriorMapLiveTest` — decode every interior map id 0..N; assert no crash;
  Coneria castle contains DOOR + STAIRS; Coneria town contains DOOR but no STAIRS.
- `ExitInteriorE2ETest` — boot ROM, reach Coneria castle, call exitInterior
  with maxSteps=80, assert phase=Overworld within 80 steps OR after ≤2 sub-map
  transitions.

**Golden:**
- Coneria castle ground-floor ASCII dump committed; regression test diffs.

## Risks / open questions

1. **`currentMapId` RAM byte** — exact address not yet confirmed. Diagnostic test
   in implementation plan: dump full RAM at known interior states (Coneria
   castle vs town vs Marsh Cave) and diff to find the byte that changes
   reliably. Likely candidate: `$0048` per FF1 RAM map docs.
2. **Tile id consistency** — assumption: same byte means same semantic across all
   interior maps. If FF1 reuses byte ids per area (e.g. byte 0x03 = wall in
   Coneria castle but treasure in Marsh Cave), classifier needs per-map tables.
   Verified by `InteriorMapLiveTest` decoding multiple maps and the live exit run.
3. **Stairs activation method** — A-press on top of tile vs walking-into. Needs
   live verification; design assumes A-press for STAIRS/WARP, walk-into for DOOR.
4. **Map sizes** — assumption is "≤ 64×64". If a map exceeds, decoder pads/truncates;
   `InteriorMapLiveTest` will surface.

## Definition of done

1. All unit tests pass.
2. `InteriorMapLiveTest` decodes all interior maps without crash.
3. `ExitInteriorE2ETest` shows agent reaches `phase=Overworld` from Coneria castle
   start in ≤ 80 steps.
4. Live evidence run: agent transitions Indoors -> Overworld and continues
   north toward the Garland bridge tile.
5. **Stretch goal**: agent reaches `Outcome.AtGarlandBattle` (the V2 long-arc
   target) in a single run.
6. CI green.
7. PR description includes evidence trace before/after.

## Future evolution (interface stable)

The new `findPathToExit` API stays; only the `SearchSpace` widens.

| Version | Search                                | Trigger                              |
|---------|---------------------------------------|--------------------------------------|
| V2.4    | Single interior map BFS to exit       | now                                  |
| V2.5    | Multi-map graph BFS / A\*             | when V2.4 thrashes between sub-maps  |
| V2.6    | Treasure-aware routing (open chests)  | when goals require items             |
| V2.7    | Combined overworld + interior planner | when planning trips between cities   |
