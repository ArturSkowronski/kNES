package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
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

            val trigger = detectTrigger(phase, ram)
            when (trigger) {
                is Trigger.NewInteriorEntered -> handleNewInterior(trigger)
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

    private fun detectTrigger(phase: FfPhase, ram: Map<String, Int>): Trigger? = when {
        phase is FfPhase.Indoors && !interiorMemory.hasMapBeenSeen(phase.mapId) ->
            Trigger.NewInteriorEntered(phase.mapId)
        phase is FfPhase.Battle -> Trigger.BattleEntered
        isDialogBoxOpen(ram) -> Trigger.DialogBoxVisible
        else -> null
    }

    /** Heuristic: dialog typically blocks input; FF1 sets specific screen-state bits.
     *  Conservative default: false. Implementation can be refined as we observe RAM. */
    private fun isDialogBoxOpen(ram: Map<String, Int>): Boolean {
        // FF1 dialog uses screenState != 0x68 (battle) and != normal-overworld marker.
        // Without a confirmed signature, return false; let interior frontier discover NPC tiles
        // via tile signatures inside InteriorMemory instead. Refine once we observe live RAM.
        return false
    }

    // Filled in next task (Task 9):
    private suspend fun handleNewInterior(t: Trigger.NewInteriorEntered) { /* Task 9 */ }
    private suspend fun handleDialog() { /* Task 9 */ }
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
