# Cross-Run Explorer — Design

**Status:** Draft, pending implementation plan
**Date:** 2026-05-05
**Branch context:** `ff1-agent-v2-4`, post V5.31 panic-reset guard
**Scope tag:** explorer-mvp / coneria-peninsula

## 1. Problem

The FF1 Koog agent (V5.x) reaches OutOfBudget every iteration around south Coneria. Root cause is not a software bug — `OverworldWarpMemory` + `FogOfWar` block warps correctly once known. The cause is **discovery cost**: each iteration spends $1-2 of Opus budget to discover 1-2 new warp tiles. Coneria's south edge has ~6-8 warp tiles total (4 currently known: (145,152), (144,153), (147,153), (147,154)). Every iter has a non-trivial chance of routing through an undiscovered warp, falling into a non-recoverable trap (mapId=0 with broken interior pathfinder), and burning the rest of the iteration budget.

A secondary issue: even when the agent is well-positioned, it must rediscover every region from scratch. There is no cross-session world model — terrain, landmarks (King throne, shops), and prior failure experience are not persisted in a usable form (warp tiles are; nothing else is).

## 2. Goal

Build a **cross-run explorer** that maps the world cheaply (Haiku 4.5, mostly deterministic) over multiple runs, accumulates persistent memory across sessions, and produces a knowledge base usable by the existing goal-driven agent in a separate phase.

This is the first half of an "explore → execute" pipeline. **Phase 2 (agent uses explorer memory) is out of scope of this spec.**

### MVP scope: Coneria Peninsula

- Overworld region: spawn (146,158) ± ~15 tiles (~30×30)
- Interior maps: Coneria Castle (mapId=1), Coneria Town (mapId=8). Possibly Temple of Fiends if reachable within budget.
- Concrete landmarks to discover: `NPC_KING` (Coneria Castle throne), `NPC_SHOPKEEPER` (Coneria Town interior).

### MVP success criteria

1. `./gradlew :knes-agent:runExplorer` exits with `outcome=Success` in ≤10 runs starting from `ff1-post-boot.savestate` (overworld 146,158).
2. After campaign: `~/.knes/ff1-overworld-terrain.json` has ≥100 tiles; `~/.knes/ff1-landmarks.json` has ≥2 visited landmarks (King + Shop); `~/.knes/ff1-blockages.json` is non-empty (proves feedback loop ran).
3. Cumulative Haiku cost <$1.00 per campaign.
4. No regression in existing test suite (currently 152 pass, 2 pre-existing failures).

## 3. Non-goals

- **Phase 2: Agent uses explorer memory.** Separate spec/plan after this MVP validates.
- **Semantic dialog parsing.** Press-A-and-screenshot heuristic only; semantic understanding is phase 2.
- **Combat optimization.** Default `battleFightAll`. FLEE / item use / target selection is phase 2.
- **Multi-region scaling.** Architecture supports it (memory grows monotonically, no Coneria-specific logic outside `KNOWN_MAP_IDS` set + savestate path), but this spec validates only Coneria.
- **Wallclock optimization.** Sequential runs, ~30-60s each. Parallel runs / snapshot replay is later.
- **UI / coverage visualizer.** Terminal printouts only.
- **Modifications to AgentSession.** Explorer is purely additive.

## 4. Architecture

```
┌─────────────────────┐         ┌──────────────────────┐
│ ExplorerSession     │ writes  │ Persistent memory    │  reads   ┌──────────────┐
│ (NEW, knes-agent)   │────────▶│ (~/.knes/*.json)     │─────────▶│ AgentSession │
│                     │         │                      │          │ (existing,   │
│ Outer: campaign     │         │ Existing:            │          │  unchanged)  │
│   loop runs runs    │         │ • ff1-overworld-     │          │              │
│ Inner: 1 run        │         │   warps.json         │          │ Reads same   │
│   (det walk +       │         │ • ff1-interior-      │          │ files via    │
│   Haiku triggers +  │         │   memory.json        │          │ existing     │
│   hard-rule         │         │ NEW:                 │          │ injection.   │
│   restart)          │         │ • ff1-overworld-     │          │              │
│                     │         │   terrain.json       │          │              │
│                     │         │ • ff1-landmarks.json │          │              │
│                     │         │ • ff1-blockages.json │          │              │
└─────────────────────┘         └──────────────────────┘          └──────────────┘
        │                                                                   │
        └────────── shared dependency ────────────────────────────┐         │
                                                                   ▼         ▼
                                       ┌─────────────────────────────────────────┐
                                       │ EmulatorToolset, Skills, ModelRouter,    │
                                       │ FogOfWar, OverworldMap, MapSession, etc. │
                                       └─────────────────────────────────────────┘
```

### Key boundary decisions

1. **Explorer and Agent never share a process.** Two `main()`s, two gradle tasks. Phase 1 runs explorer to completion → JSON files saved → phase 2 runs agent.
2. **Memory files are the only interface.** Schema-stable JSON, atomic write (write to `*.tmp`, then rename).
3. **AgentSession is unchanged in this MVP.** Explorer only writes to files; agent ignores or reads new files transparently (warp memory and interior memory are already shared, monotonically grown by both).
4. **ExplorerSession reuses ALL existing skills.** Adds one new skill: `ExploreOverworldFrontier` (deterministic salience-driven walk on overworld, analog to `ExploreInteriorFrontier`).

## 5. Memory model

All paths under `~/.knes/`. All writes atomic (temp + rename). Test injection via constructor path.

### `ff1-overworld-terrain.json` (NEW)

Persistent terrain map across runs. After each run, `FogOfWar.seen` is merged into this file.

```json
{
  "tiles": {
    "146,158": "PLAINS",
    "146,157": "PLAINS",
    "147,154": "TOWN",
    "152,150": "FOREST"
  },
  "lastUpdated": "2026-05-05T..."
}
```

**Class:** `OverworldTerrainMemory` (mirrors `OverworldWarpMemory` shape).
**API:** `record(x,y,TileType)`, `tileAt(x,y): TileType?`, `seenCount()`, `bbox(): Pair<Pair,Pair>?`, `merge(viewport: ViewportMap): Int` (returns count of newly added tiles), `frontierTilesNear(center, radius): Sequence<Pair<Int,Int>>`, `save()`, `load()` at construction.

### `ff1-landmarks.json` (NEW)

List of identified objects of interest. Auto-extracted from terrain (TOWN, CASTLE) and from interior exploration (NPC tiles, dialog interactions).

```json
{
  "landmarks": [
    {
      "id": "coneria_town_entry_147_154",
      "kind": "TOWN_ENTRY",
      "worldX": 147, "worldY": 154,
      "mapIdInterior": 8,
      "visited": true,
      "discoveredRunId": "run-1-2026-05-05T..."
    },
    {
      "id": "coneria_castle_throne_npc_king",
      "kind": "NPC_KING",
      "mapId": 1,
      "localX": 14, "localY": 4,
      "visited": true,
      "note": "talked: bridge to be built after Garland"
    }
  ]
}
```

**Class:** `LandmarkMemory`.
**API:** `record(Landmark)`, `recordIfNew(...)`, `findByKind(kind): List<Landmark>`, `findByLocation(...)`, `atLocation(worldX, worldY): Landmark?`, `markVisited(id)`, `save()`, `load()` at construction.

**Initial Kind enum:**
- `TOWN_ENTRY`, `CASTLE_ENTRY`, `DUNGEON_ENTRY` — auto from overworld tile types.
- `NPC_KING`, `NPC_SHOPKEEPER`, `NPC_GENERIC` — interior, classified post-explore via Haiku from screenshot + visited tile context.
- `STAIRS_UP`, `STAIRS_DOWN`, `EXIT_TILE` — interior tile-type signals.

Enum is extensible. Unknown kinds in JSON do not break load (forward-compat).

### `ff1-blockages.json` (NEW)

Append-only log of failed attempts. Drives "don't repeat the same mistake" loop.

```json
{
  "blockages": [
    {"runId": "run-1-...", "from": [145,153], "attemptedTo": [148,151], "result": "BFS no path within viewport", "ts": "..."},
    {"runId": "run-2-...", "from": [16,17], "attemptedTo": "exit", "result": "exitInterior maxSteps reached, mapId=8", "ts": "..."},
    {"runId": "run-3-...", "from": [147,154], "attemptedTo": "exit interior", "result": "mapId=0 trap, restart triggered", "ts": "..."}
  ],
  "runStartDirections": {
    "run-1-...": "N",
    "run-2-...": "N",
    "run-3-...": "E"
  }
}
```

**Class:** `BlockageMemory`.
**API:** `record(Blockage)`, `recentBlockagesNear(tile, radiusTiles, withinDuration)`, `recentFailures(within: Duration): List<Blockage>`, `recordRunStartDirection(runId, dir)`, `pathTriedRecentDirections(k: Int): List<Direction>`, `save()`, `load()`.

`runStartDirections` is folded into this file rather than introducing a 6th file.

### `ff1-overworld-warps.json` (EXISTING — backward-compat extension)

Already used by AgentSession. Add optional `mapIdDestination: Int?` field. Loader tolerates absence.

### `ff1-interior-memory.json` (EXISTING — no changes)

Already adequate. Per-mapId visited tile sets.

### Coverage metric (in-process, not a file)

After each run, ExplorerSession prints:

```
[campaign] run #3 done. terrain: +12 tiles (total 87), landmarks: +1 (3), warps: +0 (4), blockages: +2 (7)
```

`CoverageStats` is a derivative computed from current memory state.

## 6. Outer loop (campaign)

```kotlin
class ExplorerSession(
    toolset: EmulatorToolset,
    observer: RamObserver,
    landmarkMemory: LandmarkMemory,
    terrainMemory: OverworldTerrainMemory,
    blockageMemory: BlockageMemory,
    warpMemory: OverworldWarpMemory,
    interiorMemory: InteriorMemory,
    fog: FogOfWar,
    skillRegistry: SkillRegistry,
    haikuConsult: HaikuConsult,
    savestatePath: Path,
    campaignBudget: CampaignBudget = CampaignBudget(),
)

data class CampaignBudget(
    val maxRuns: Int = 20,
    val maxWallClockMinutes: Int = 30,
    val coveragePlateauRuns: Int = 3,
    val maxTotalCostUsd: Double = 1.0,
)

data class CampaignResult(
    val outcome: Outcome,             // Success | Plateau | OutOfBudget | OutOfRuns
    val runsExecuted: Int,
    val coverage: CoverageStats,
    val goalsFound: Set<String>,
)
```

**Pseudocode:**

```kotlin
suspend fun run(): CampaignResult {
    var runs = 0
    var consecutiveZeroProgress = 0
    var totalCostUsd = 0.0
    val campaignStart = System.currentTimeMillis()

    while (true) {
        if (runs >= budget.maxRuns) return result(OutOfRuns)
        if (elapsedMin() >= budget.maxWallClockMinutes) return result(OutOfBudget)
        if (totalCostUsd >= budget.maxTotalCostUsd) return result(OutOfBudget)
        if (consecutiveZeroProgress >= budget.coveragePlateauRuns) return result(Plateau)
        if (goalsAchieved()) return result(Success)

        toolset.loadState(savestatePath)
        val coverageBefore = snapshotCoverage()
        val runId = "run-${runs+1}-${Instant.now()}"

        val singleRun = SingleRun(this, runId)
        val runResult = singleRun.execute()

        // persist all memory atomically
        terrainMemory.save(); landmarkMemory.save(); blockageMemory.save()
        warpMemory.save(); interiorMemory.save()

        val coverageAfter = snapshotCoverage()
        val delta = coverageAfter - coverageBefore
        consecutiveZeroProgress = if (delta.hasNoNewDiscoveries()) consecutiveZeroProgress + 1 else 0
        totalCostUsd += runResult.haikuCostUsd
        runs += 1

        log("[campaign] $runId done: $delta; consec-zero=$consecutiveZeroProgress; cost=$$totalCostUsd")
    }
}

fun goalsAchieved(): Boolean {
    val king = landmarkMemory.findByKind(NPC_KING).firstOrNull { it.visited }
    val shop = landmarkMemory.findByKind(NPC_SHOPKEEPER).firstOrNull { it.visited }
    return king != null && shop != null
}
```

**Invariants:**
- Memory grows monotonically across runs (no deletions).
- Each run starts from identical RAM state via `loadState`. Determinism for debug.
- No in-process state shared between runs except memory; everything else flushed to disk between runs.
- Crash safety: per-run save() at trigger handler completion + end-of-run.

## 7. Inner loop (single run)

```kotlin
data class RunResult(val stopReason: StopReason, val haikuCostUsd: Double)

enum class StopReason { PartyWiped, UnknownMapTrap, Idle, LocalPlateau, MaxSteps, GoalAchievedEarly }

class SingleRun(private val session: ExplorerSession, private val runId: String) {
    private var idleTurns = 0
    private var coverageDeltaInLastNTurns = 0
    private val coverageWindow = 10
    private var haikuCostUsd = 0.0

    suspend fun execute(): RunResult {
        var stepsTaken = 0
        val maxSteps = 200

        while (stepsTaken < maxSteps) {
            val ram = observer.ramSnapshot()
            val phase = observer.observeWithVision()

            checkRestart(ram, phase)?.let { return RunResult(it, haikuCostUsd) }

            val trigger = detectTrigger(phase, ram)
            when (trigger) {
                is Trigger.NewInteriorEntered -> handleNewInterior(trigger)
                is Trigger.DialogBoxVisible  -> handleDialog(trigger)
                is Trigger.BattleEntered     -> handleBattle(trigger)
                null -> deterministicStep(phase, ram)
            }

            stepsTaken++
        }
        return RunResult(StopReason.MaxSteps, haikuCostUsd)
    }
}
```

### Restart triggers (hard rules, ordered)

```kotlin
fun checkRestart(ram, phase): StopReason? {
    val mapflags = (ram["mapflags"] ?: 0) and 0x01
    val mapId = ram["currentMapId"] ?: -1
    val partyHpSum = (1..4).sumOf { ram["char${it}_hpLow"] ?: 0 }

    return when {
        partyHpSum == 0 && mapflags != 0 -> StopReason.PartyWiped
        mapflags == 1 && mapId !in KNOWN_MAP_IDS && consecutiveIdleInTrap >= 3 -> StopReason.UnknownMapTrap
        idleTurns >= 10 -> StopReason.Idle
        coverageDeltaInLastNTurns == 0 && stepsTaken > coverageWindow -> StopReason.LocalPlateau
        else -> null
    }
}

val KNOWN_MAP_IDS = setOf(1 /* Coneria Castle */, 8 /* Coneria Town */)
// 0 is overworld and only valid when mapflags=0; treated as trap when mapflags=1
```

Restart does NOT call `loadState`. Returns `StopReason`. Outer loop handles state reset on next run start. Separation of concerns.

### Trigger detection

```kotlin
sealed interface Trigger {
    data class NewInteriorEntered(val mapId: Int, val firstTime: Boolean) : Trigger
    data class DialogBoxVisible(val approxText: String?) : Trigger
    object BattleEntered : Trigger
}

fun detectTrigger(phase, ram): Trigger? = when {
    phase is FfPhase.Indoors -> {
        val firstTime = !interiorMemory.hasMapBeenSeen(phase.mapId)
        if (firstTime) Trigger.NewInteriorEntered(phase.mapId, true) else null
    }
    phase is FfPhase.Battle -> Trigger.BattleEntered
    isDialogBoxOpen(ram) -> Trigger.DialogBoxVisible(approxText = null)
    else -> null
}
```

`isDialogBoxOpen` heuristic — implementation choice deferred to plan. Likely `screenState` byte signature or RAM byte. If unclear in plan phase, fall back to "if pressing A reduces visible text region, dialog is open" — visual heuristic.

### Deterministic step (90%+ of cases, no LLM)

```kotlin
suspend fun deterministicStep(phase, ram) {
    when (phase) {
        is FfPhase.TitleOrMenu, NewGameMenu, NameEntry -> skillRegistry.pressStartUntilOverworld()
        is FfPhase.Overworld -> {
            val target = pickOverworldTarget(ram)
            skillRegistry.exploreOverworldFrontier(target)  // NEW skill
        }
        is FfPhase.Indoors -> skillRegistry.exploreInteriorFrontier()  // existing
        is FfPhase.PostBattle -> skillRegistry.battleFightAll()
        else -> { idleTurns++ }
    }
    updateCoverage()
}
```

### Trigger handlers (Haiku consults; short, focused)

```kotlin
suspend fun handleNewInterior(trigger: NewInteriorEntered) {
    landmarkMemory.recordIfNew(...)  // entry tile = TOWN_ENTRY/CASTLE_ENTRY
    skillRegistry.exploreInteriorFrontier(maxSteps = 80)
    // post-explore: Haiku classifies what we found
    val classification = haikuConsult.classifyInterior(
        mapId, visitedTiles.size, screenshot, prompt = "Identify landmarks: King throne / Shop / Inn / Generic NPC / Stairs."
    )
    haikuCostUsd += classification.costUsd
    classification.landmarks.forEach { landmarkMemory.record(it) }
}

suspend fun handleDialog(trigger: DialogBoxVisible) {
    val text = grabDialogText()  // press A repeatedly, screenshot each, Haiku reads
    haikuCostUsd += text.costUsd
    landmarkMemory.recordIfNew(NPC_GENERIC at currentTile, note = text.summary)
    pressUntilDialogClosed()
}

suspend fun handleBattle(trigger: BattleEntered) {
    skillRegistry.battleFightAll()  // no LLM
}
```

### Coverage update (every step)

```kotlin
fun updateCoverage() {
    val ram = observer.ramSnapshot()
    val (cx, cy) = ram["worldX"]!! to ram["worldY"]!!
    val viewport = overworldMap.readFullMapView(cx to cy)
    val newTiles = terrainMemory.merge(viewport)
    coverageDeltaInLastNTurns = (rolling window updated)
    if (newTiles > 0) idleTurns = 0
}
```

### Crash safety

`save()` on every memory class after each trigger handler completion + end-of-run. Worst-case loss: 1 step before crash.

## 8. Salience strategy (`pickOverworldTarget`)

```
1. UNVISITED_LANDMARK  — known TOWN/CASTLE_ENTRY landmark with visited=false
2. SALIENT_VIEWPORT    — TOWN/CASTLE/DUNGEON tile in current viewport not yet recorded as landmark
3. UNMAPPED_FRONTIER   — nearest passable tile adjacent to UNKNOWN tile
4. CROSS_RUN_DIVERSIFY — if last K runs all started in direction D, pick a different cardinal
5. WANDER              — random walkable tile (degenerate fallback)
```

```kotlin
fun pickOverworldTarget(ram: Map<String,Int>): Pair<Int,Int> {
    val (cx, cy) = ram["worldX"]!! to ram["worldY"]!!
    val viewport = overworldMap.readFullMapView(cx to cy)
    fog.merge(viewport)
    terrainMemory.mergeFromViewport(viewport)

    // Priority 1
    landmarkMemory.findByKind(TOWN_ENTRY, CASTLE_ENTRY, DUNGEON_ENTRY)
        .filter { !it.visited && (it.worldX to it.worldY) !in recentlyFailedTargets() }
        .minByOrNull { manhattanDistance(cx to cy, it.worldX to it.worldY) }
        ?.let { return it.worldX to it.worldY }

    // Priority 2
    viewport.findTilesOfType(setOf(TOWN, CASTLE, DUNGEON))
        .filter { (wx, wy) -> landmarkMemory.atLocation(wx, wy) == null }
        .filter { it !in recentlyFailedTargets() }
        .minByOrNull { manhattanDistance(cx to cy, it) }
        ?.let {
            landmarkMemory.record(Landmark(kind=guessKindFromTile(it), x=it.first, y=it.second, visited=false))
            return it
        }

    // Priority 3
    terrainMemory.frontierTilesNear(cx to cy, radius = 20)
        .filter { it !in recentlyFailedTargets() && fog.isPassable(it) }
        .minByOrNull { manhattanDistance(cx to cy, it) }
        ?.let { return it }

    // Priority 4
    val recent = blockageMemory.pathTriedRecentDirections(k = 3)
    val available = listOf(N, E, S, W).filter { it !in recent }
    available.firstOrNull()?.let { dir -> return cx + dir.dx * 8 to cy + dir.dy * 8 }

    // Priority 5
    return randomWalkableTileInViewport(viewport, fog)
}
```

**Blockage feedback loop:** when `walkOverworldTo` returns BLOCKED for target T, `blockageMemory.record(from=current, attemptedTo=T, result=...)`. Next `pickOverworldTarget` filters T from candidates within recent-window.

**Cross-run diversification:** `runStartDirection` is logged in `BlockageMemory` per run. If last 3 runs all started N, priority 4 forces E or W in next run.

### Expected campaign trajectory for Coneria Peninsula

- Run 1: walk N from spawn → CASTLE tile in viewport (priority 2) → entry → mapId=1 → handleNewInterior → throne with NPC_KING discovered → goal.
- Run 2: spawn → walk to Castle (now visited landmark, skip) → priority 2 finds TOWN tile → mapId=8 entry → handleNewInterior → shop counter NPC_SHOPKEEPER discovered → goal.
- Run 3+: terrain coverage continues but `goalsAchieved() == true` already → outer loop exits Success.

Realistic: 2-5 runs to success, ~$0.05-0.15 per run, total campaign ~$0.20-0.75.

### mapId=0 trap behavior

Still possible (V5.30 destination-OK on fog-blocked tiles allows deliberate entry into warps that may turn out to be unmappable traps). Hard rule restart trigger detects after 3 idle turns in `mapId !in KNOWN_MAP_IDS && mapflags=1`, records blockage with `attemptedTo="exit interior"`, `result="mapId=N trap"`. Next run's salience deprioritizes that warp tile.

## 9. Entry points

**New file:** `knes-agent/src/main/kotlin/knes/agent/explorer/ExplorerMain.kt`

```kotlin
fun main() {
    val rom = System.getenv("FF1_ROM") ?: defaultRomPath
    val savestatePath = Path.of(System.getenv("FF1_SAVESTATE") ?: defaultPostBootSavestate)
    val session = ExplorerSession.fromDefaults(rom, savestatePath)
    val result = runBlocking { session.run() }
    println("[campaign] FINAL: $result")
    exitProcess(if (result.outcome == Outcome.Success) 0 else 1)
}
```

**Gradle:** new task `runExplorer` in `knes-agent/build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("runExplorer") {
    mainClass.set("knes.agent.explorer.ExplorerMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
```

Usage:

```bash
./gradlew :knes-agent:runExplorer    # phase 1: build memory
./gradlew :knes-agent:runAgent       # phase 2: existing, uses memory
```

Each task is a fresh JVM. Memory on disk is the entire interface.

## 10. Testing strategy

Three levels:

### Pure-unit (no ROM)

- `OverworldTerrainMemoryTest` — round-trip JSON, atomic save, merge semantics, query API.
- `LandmarkMemoryTest` — record / recordIfNew / findByKind / atLocation / markVisited.
- `BlockageMemoryTest` — append, recentFailures with time window, runStartDirection logging.

~6-8 tests total.

### Live single-run (with ROM)

- `SingleRunOverworldTest` — load `ff1-post-boot.savestate`, run ≤30 steps, assert `terrainMemory.size > 0` and any TOWN/CASTLE in viewport are recorded as landmarks.
- `SingleRunInteriorTest` — load fixture inside Coneria Castle, assert `handleNewInterior` triggers, post-explore landmark count > 0 (with fake HaikuConsult returning NPC_KING).
- `RestartTriggerTest` — fixture savestate inside mapId=0 trap, assert `StopReason.UnknownMapTrap` after 3 idle turns. May need new fixture `ff1-mapId0-trap.savestate`.

Tests skip via `if (!File(rom).exists()) return@test` (matches existing convention).

~4-5 tests total.

### Live campaign (Haiku, opt-in)

- `ExplorerCampaignLiveTest` — full campaign with real Haiku, marked `@Tag("live")`, default skipped, manually invoked via `./gradlew :knes-agent:test --tests "*Live*"` with `ANTHROPIC_API_KEY` set. Asserts: `goalsAchieved == true` in ≤10 runs, cost <$1.00.

Not in CI. Used for end-to-end validation by hand.

### What we do NOT mock

- Not the LLM at the SDK level. Trigger handlers take a `HaikuConsult` interface; unit tests use `FakeHaikuConsult` returning canned results. Live tests use real Anthropic call.
- Not the NES emulator. Real `EmulatorSession` + real ROM, matching existing test patterns.

## 11. Components delivered (summary)

1. **5 new Kotlin classes** in `knes-agent/src/main/kotlin/knes/agent/`:
   - `explorer/ExplorerSession.kt`
   - `explorer/SingleRun.kt`
   - `perception/OverworldTerrainMemory.kt`
   - `perception/LandmarkMemory.kt`
   - `perception/BlockageMemory.kt`
2. **1 new skill:** `skills/ExploreOverworldFrontier.kt` (analog to `ExploreInteriorFrontier`).
3. **1 thin entry point:** `explorer/ExplorerMain.kt` + gradle task `runExplorer`.
4. **~10 tests** (6-8 pure-unit + 4-5 live single-run; 1 live campaign opt-in).
5. **Doc note in `HANDOFF.md`** that flow is "explorer → agent".

Plus minor backward-compat extension to `OverworldWarpMemory` (optional `mapIdDestination` field).

## 12. Risks and mitigations

- **Haiku misclassifies interior NPCs.** Default to `NPC_GENERIC` with note. Phase 2 can re-classify with better data. Doesn't block MVP success criteria (King + Shop are big enough that Haiku 4.5 should reliably distinguish — throne tile + crown sprite for King, counter tile + multiple item sprites for shop).
- **`isDialogBoxOpen` heuristic wrong.** Implementation plan resolves. If ambiguous, fall back to "screenshot diff after press A" approach.
- **mapId=0 trap consumes restart budget.** Hard cap maxRuns=20 prevents infinite trap loop. Blockage memory records prevent re-targeting same tile.
- **Coneria has more warps than expected.** Each discovered warp eliminates a future run trap. Worst case: 8 warps → 8 runs to discover all → still within `maxRuns=20`.
- **Savestate diverges from current ROM.** Existing fixtures `ff1-post-boot.savestate` already work in current tests; this MVP uses same.

## 13. Out-of-scope tracking (future specs)

- Phase 2: `AgentSession` reads `landmarks.json` and uses landmark IDs as goal references (`reach NPC_KING`, `route via warp tile to mapId=8`).
- Phase 2: optimal route caching — explorer outputs a derived `routes.json` mapping (start, goal_id) → step sequence. Agent replays directly.
- Phase 3: scaling — explorer for Pravoka, Elfheim, Marsh Cave, etc. Same architecture, different `KNOWN_MAP_IDS`, different savestate per region (or chained explorations).
- Phase 4: combat policy module — separate from explorer's `battleFightAll` default.
