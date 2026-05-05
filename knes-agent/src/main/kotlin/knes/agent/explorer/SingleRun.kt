package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.RamObserver
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

enum class StopReason { PartyWiped, UnknownMapTrap, Idle, LocalPlateau, MaxSteps, GoalAchievedEarly }

data class RunResult(val stopReason: StopReason, val haikuCostUsd: Double, val stepsTaken: Int)

sealed interface Trigger {
    data class NewInteriorEntered(val mapId: Int) : Trigger
    object DialogBoxVisible : Trigger
    object BattleEntered : Trigger
}

class SingleRun(
    private val runId: String,
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val overworldMap: OverworldMap,
    private val skillRegistry: SkillRegistry,
    private val terrainMemory: OverworldTerrainMemory,
    private val landmarkMemory: LandmarkMemory,
    private val blockageMemory: BlockageMemory,
    private val interiorMemory: InteriorMemory,
    private val fog: FogOfWar,
    private val salience: SalienceStrategy,
    private val haikuConsult: HaikuConsult,
    private val knownMapIds: Set<Int> = setOf(1, 8),
    private val maxSteps: Int = 200,
    private val coverageWindow: Int = 10,
) {
    private var idleTurns = 0
    private var consecutiveIdleInTrap = 0
    private var lastRam: Map<String, Int> = emptyMap()
    private var haikuCostUsd = 0.0
    private val coverageDeltaWindow: ArrayDeque<Int> = ArrayDeque()
    private var firstStartDirection: String? = null
    /** Per-run set of mapIds for which `NewInteriorEntered` has already fired this run.
     *  Replaces persistent `interiorMemory.hasMapBeenSeen` gating — that prevented Haiku
     *  classification ever firing on previously-seen maps across sessions. Now bounded
     *  by ~3-5 maps per run; each run gets one classification per visited interior. */
    private val novelMapIdsThisRun: MutableSet<Int> = mutableSetOf()

    suspend fun execute(): RunResult {
        var stepsTaken = 0
        while (stepsTaken < maxSteps) {
            val ram = observer.ramSnapshot()
            val phase = observer.observeWithVision()

            updateIdleTracking(ram)
            val coverageDelta = coverageDeltaWindow.sumOf { it }

            checkRestart(
                ram = ram, knownMapIds = knownMapIds,
                consecutiveIdleInTrap = consecutiveIdleInTrap, idleTurns = idleTurns,
                stepsTaken = stepsTaken, coverageWindow = coverageWindow,
                coverageDeltaInWindow = coverageDelta,
            )?.let { reason ->
                blockageMemory.record(runId,
                    from = (ram["worldX"] ?: -1) to (ram["worldY"] ?: -1),
                    attemptedTo = "<run end>",
                    result = reason.name)
                return RunResult(reason, haikuCostUsd, stepsTaken)
            }

            val trigger = detectTrigger(phase, ram, novelMapIdsThisRun, ::isDialogBoxOpen)
            when (trigger) {
                is Trigger.NewInteriorEntered -> {
                    novelMapIdsThisRun += trigger.mapId
                    handleNewInterior(trigger)
                }
                Trigger.DialogBoxVisible -> handleDialog()
                Trigger.BattleEntered -> handleBattle()
                null -> deterministicStep(phase, ram)
            }
            stepsTaken++
        }
        return RunResult(StopReason.MaxSteps, haikuCostUsd, stepsTaken)
    }

    private fun updateIdleTracking(ram: Map<String, Int>) {
        if (ram == lastRam) idleTurns++ else idleTurns = 0
        lastRam = ram
        val mapflags = (ram["mapflags"] ?: 0) and 0x01
        val mapId = ram["currentMapId"] ?: -1
        if (mapflags == 1 && mapId !in knownMapIds) consecutiveIdleInTrap++
        else consecutiveIdleInTrap = 0
    }

    private fun recordCoverageDelta(delta: Int) {
        coverageDeltaWindow.addLast(delta)
        while (coverageDeltaWindow.size > coverageWindow) coverageDeltaWindow.removeFirst()
        if (delta > 0) idleTurns = 0
    }

    /** Heuristic: dialog typically blocks input; FF1 sets specific screen-state bits.
     *  Conservative default: false. Implementation can be refined as we observe RAM. */
    private fun isDialogBoxOpen(ram: Map<String, Int>): Boolean {
        // FF1 dialog uses screenState != 0x68 (battle) and != normal-overworld marker.
        // Without a confirmed signature, return false; let interior frontier discover NPC tiles
        // via tile signatures inside InteriorMemory instead. Refine once we observe live RAM.
        return false
    }

    private suspend fun handleNewInterior(t: Trigger.NewInteriorEntered) {
        val ram = observer.ramSnapshot()
        // Pre-record the entry as a TOWN/CASTLE/DUNGEON_ENTRY landmark (visited=true).
        val cx = ram["worldX"] ?: 0; val cy = ram["worldY"] ?: 0
        landmarkMemory.recordIfNew(Landmark(
            id = "interior_entry_${t.mapId}_${cx}_${cy}",
            kind = guessEntryKind(t.mapId),
            worldX = cx, worldY = cy,
            mapIdInterior = t.mapId,
            visited = true,
            discoveredRunId = runId,
        ))
        // Run the deterministic interior explorer.
        skillRegistry.exploreInteriorFrontier(maxSteps = 80)
        // Post-explore: ask Haiku to classify what we saw.
        val visited = interiorMemory.visited(t.mapId)
        val b64 = runCatching { toolset.getScreen().base64 }.getOrNull()
        val classification = haikuConsult.classifyInterior(
            mapId = t.mapId, visitedTileCount = visited.size, screenshotBase64 = b64, runId = runId,
        )
        haikuCostUsd += applyInteriorClassification(landmarkMemory, classification)
    }

    private suspend fun handleDialog() {
        // Press A once to advance, take screenshot, ask Haiku to read.
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
        val b64 = runCatching { toolset.getScreen().base64 }.getOrNull()
        val reading = haikuConsult.readDialog(screenshotBase64 = b64)
        haikuCostUsd += reading.costUsd
        if (reading.landmarkHint != null) {
            val ram = observer.ramSnapshot()
            val mapId = ram["currentMapId"] ?: -1
            val lx = ram["smPlayerX"]; val ly = ram["smPlayerY"]
            landmarkMemory.recordIfNew(Landmark(
                id = "dialog_${reading.landmarkHint.lowercase()}_${mapId}_${lx}_${ly}",
                kind = kindFromHint(reading.landmarkHint),
                mapId = mapId, localX = lx, localY = ly,
                visited = true, note = reading.summary, discoveredRunId = runId,
            ))
        }
    }

    private fun guessEntryKind(mapId: Int): LandmarkKind = when (mapId) {
        1 -> LandmarkKind.CASTLE_ENTRY    // Coneria Castle
        8 -> LandmarkKind.TOWN_ENTRY      // Coneria Town
        else -> LandmarkKind.DUNGEON_ENTRY
    }

    private fun kindFromHint(hint: String): LandmarkKind = when (hint.uppercase()) {
        "KING" -> LandmarkKind.NPC_KING
        "SHOP", "SHOPKEEPER" -> LandmarkKind.NPC_SHOPKEEPER
        else -> LandmarkKind.NPC_GENERIC
    }

    private suspend fun handleBattle() { skillRegistry.battleFightAll() }

    private suspend fun deterministicStep(phase: FfPhase, ram: Map<String, Int>) {
        when (phase) {
            is FfPhase.TitleOrMenu, FfPhase.NewGameMenu, FfPhase.NameEntry, FfPhase.Boot ->
                skillRegistry.pressStartUntilOverworld()
            is FfPhase.Overworld -> {
                val cx = ram["worldX"] ?: 0; val cy = ram["worldY"] ?: 0
                val viewport = overworldMap.readFullMapView(cx to cy)
                fog.merge(viewport)
                val newTiles = terrainMemory.merge(viewport)
                recordCoverageDelta(newTiles)
                if (firstStartDirection == null) {
                    firstStartDirection = pickInitialCardinal(cx to cy, viewport)
                    blockageMemory.recordRunStartDirection(runId, firstStartDirection ?: "?")
                }
                val target = salience.pickOverworldTarget(currentXY = cx to cy, viewport = viewport)
                val res = skillRegistry.exploreOverworldFrontier(target.first, target.second)
                if (!res.ok) {
                    blockageMemory.record(runId, from = cx to cy,
                        attemptedTo = "${target.first},${target.second}",
                        result = res.message)
                    // Pathfinder rejected the target as impassable / no-path. Salience
                    // priority 2 records ANY TOWN/CASTLE-typed viewport tile as a Landmark,
                    // but the OverworldTileClassifier conflates entry tiles with wall
                    // decoration. When BFS confirms the tile isn't actually walkable, drop
                    // the bogus tile-tagged landmark so the next salience pick doesn't
                    // re-target the same dead end. Confirmed entries (mapIdInterior != null)
                    // are preserved.
                    if (looksUnreachable(res.message)) {
                        landmarkMemory.removeTileTaggedAt(target.first, target.second)
                    }
                }
            }
            is FfPhase.Indoors -> skillRegistry.exploreInteriorFrontier()
            is FfPhase.PostBattle -> skillRegistry.battleFightAll()
            else -> idleTurns++  // unknown phase
        }
    }

    private fun pickInitialCardinal(currentXY: Pair<Int, Int>, viewport: knes.agent.perception.ViewportMap): String {
        val target = salience.pickOverworldTarget(currentXY, viewport)
        val dx = target.first - currentXY.first; val dy = target.second - currentXY.second
        return when {
            kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx > 0) "E" else "W"
            else -> if (dy > 0) "S" else "N"
        }
    }

    companion object {
        /** Pathfinder messages indicating the target is unreachable from current position.
         *  Used to purge bogus tile-tagged landmarks that the OverworldTileClassifier
         *  misidentified as TOWN/CASTLE entries. Visible-for-test. */
        fun looksUnreachable(message: String): Boolean =
            "impassable terrain" in message || "no path within viewport" in message

        /** Per-run novelty trigger detector. Pure function — no side effects.
         *  Caller adds the mapId to its `novelMapIdsThisRun` set after firing.
         *  Replaces the previous `interiorMemory.hasMapBeenSeen` gating which
         *  was persistent across sessions and starved Haiku classification. */
        fun detectTrigger(
            phase: FfPhase,
            ram: Map<String, Int>,
            novelMapIdsThisRun: Set<Int>,
            isDialogBoxOpen: (Map<String, Int>) -> Boolean = { false },
        ): Trigger? = when {
            phase is FfPhase.Indoors && phase.mapId !in novelMapIdsThisRun ->
                Trigger.NewInteriorEntered(phase.mapId)
            phase is FfPhase.Battle -> Trigger.BattleEntered
            isDialogBoxOpen(ram) -> Trigger.DialogBoxVisible
            else -> null
        }

        /** Pure helper used by handleNewInterior; testable in isolation. */
        fun applyInteriorClassification(
            landmarkMemory: LandmarkMemory,
            classification: HaikuConsult.InteriorClassification,
        ): Double {
            classification.landmarks.forEach { landmarkMemory.recordIfNew(it) }
            return classification.costUsd
        }

        fun checkRestart(
            ram: Map<String, Int>,
            knownMapIds: Set<Int>,
            consecutiveIdleInTrap: Int,
            idleTurns: Int,
            stepsTaken: Int,
            coverageWindow: Int,
            coverageDeltaInWindow: Int,
        ): StopReason? {
            val mapflags = (ram["mapflags"] ?: 0) and 0x01
            val mapId = ram["currentMapId"] ?: -1
            val partyHpSum = (1..4).sumOf { ram["char${it}_hpLow"] ?: 0 }
            return when {
                partyHpSum == 0 && mapflags == 1 -> StopReason.PartyWiped
                mapflags == 1 && mapId !in knownMapIds && consecutiveIdleInTrap >= 3 -> StopReason.UnknownMapTrap
                idleTurns >= 10 -> StopReason.Idle
                coverageDeltaInWindow == 0 && stepsTaken > coverageWindow -> StopReason.LocalPlateau
                else -> null
            }
        }
    }
}
