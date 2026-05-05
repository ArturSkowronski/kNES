package knes.agent.explorer

import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.time.Instant

data class CampaignBudget(
    val maxRuns: Int = 20,
    val maxWallClockMinutes: Int = 30,
    val coveragePlateauRuns: Int = 3,
    val maxTotalCostUsd: Double = 1.0,
)

enum class CampaignOutcome { Success, Plateau, OutOfBudget, OutOfRuns }

data class CoverageStats(
    val terrainTilesKnown: Int,
    val warpsKnown: Int,
    val landmarksKnown: Int,
    val landmarksVisited: Int,
    val interiorMapsExplored: Int,
) {
    operator fun minus(other: CoverageStats) = CoverageStats(
        terrainTilesKnown - other.terrainTilesKnown,
        warpsKnown - other.warpsKnown,
        landmarksKnown - other.landmarksKnown,
        landmarksVisited - other.landmarksVisited,
        interiorMapsExplored - other.interiorMapsExplored,
    )
    fun hasNoNewDiscoveries(): Boolean =
        terrainTilesKnown <= 0 && warpsKnown <= 0 && landmarksKnown <= 0 &&
        landmarksVisited <= 0 && interiorMapsExplored <= 0
}

data class CampaignResult(
    val outcome: CampaignOutcome,
    val runsExecuted: Int,
    val coverage: CoverageStats,
    val totalCostUsd: Double,
)

/**
 * Outer campaign loop: fires [SingleRun.execute] repeatedly, persists memory between runs,
 * and decides when to stop (Success / OutOfRuns / OutOfBudget / Plateau).
 *
 * [savedState] is the raw ByteArray from [EmulatorSession.saveState] captured just before the
 * campaign starts (overworld, party alive). It is fed back to [EmulatorSession.loadState] at
 * the top of each run so every run starts from the same clean state.
 */
class ExplorerSession(
    private val toolset: EmulatorToolset,
    private val emulatorSession: EmulatorSession,
    private val observer: RamObserver,
    private val overworldMap: OverworldMap,
    private val skillRegistry: SkillRegistry,
    private val terrainMemory: OverworldTerrainMemory,
    private val landmarkMemory: LandmarkMemory,
    private val blockageMemory: BlockageMemory,
    private val warpMemory: OverworldWarpMemory,
    private val interiorMemory: InteriorMemory,
    private val fog: FogOfWar,
    private val haikuConsult: HaikuConsult,
    private val savedState: ByteArray,
    private val budget: CampaignBudget = CampaignBudget(),
) {
    private val salience = SalienceStrategy(terrainMemory, landmarkMemory, blockageMemory, fog, warpMemory)

    suspend fun run(): CampaignResult {
        var runs = 0
        var consecutiveZero = 0
        var totalCost = 0.0
        val start = System.currentTimeMillis()

        while (true) {
            if (goalsAchieved(landmarkMemory)) return result(CampaignOutcome.Success, runs, totalCost)
            if (runs >= budget.maxRuns) return result(CampaignOutcome.OutOfRuns, runs, totalCost)
            if (elapsedMin(start) >= budget.maxWallClockMinutes) return result(CampaignOutcome.OutOfBudget, runs, totalCost)
            if (totalCost >= budget.maxTotalCostUsd) return result(CampaignOutcome.OutOfBudget, runs, totalCost)
            if (consecutiveZero >= budget.coveragePlateauRuns) return result(CampaignOutcome.Plateau, runs, totalCost)

            val runId = "run-${runs + 1}-${Instant.now()}"
            check(emulatorSession.loadState(savedState)) {
                "loadState failed for $runId — savedState may be corrupt or version-incompatible"
            }
            // V5.2 loadState quirk: emulator drops a few input frames after loadState.
            // Without warm-up the first walkOverworldTo step doesn't move the party
            // (3 consecutive non-moving steps trip the "input not responding" guard),
            // every warp the agent targets fails on iteration 1, idle threshold fires
            // before recovery, every overworld campaign plateaus at 0 entries.
            // 30 NOOP frames (~0.5s) bridge the threshold.
            toolset.step(buttons = emptyList(), frames = WARMUP_FRAMES)
            val before = snapshotCoverage()
            println("[campaign] starting $runId; coverage=$before")

            val singleRun = SingleRun(
                runId = runId, toolset = toolset, observer = observer,
                overworldMap = overworldMap, skillRegistry = skillRegistry,
                terrainMemory = terrainMemory, landmarkMemory = landmarkMemory,
                blockageMemory = blockageMemory, interiorMemory = interiorMemory,
                fog = fog, salience = salience, haikuConsult = haikuConsult,
            )
            val runResult = singleRun.execute()

            terrainMemory.save(); landmarkMemory.save(); blockageMemory.save()
            warpMemory.save(); interiorMemory.save()

            val after = snapshotCoverage()
            val delta = after - before
            consecutiveZero = if (delta.hasNoNewDiscoveries()) consecutiveZero + 1 else 0
            totalCost += runResult.haikuCostUsd
            runs++
            println("[campaign] $runId done: stop=${runResult.stopReason} delta=$delta consec-zero=$consecutiveZero cost=$$totalCost")
        }
    }

    private fun snapshotCoverage(): CoverageStats = CoverageStats(
        terrainTilesKnown = terrainMemory.seenCount(),
        warpsKnown = warpMemory.all().size,
        landmarksKnown = landmarkMemory.all().size,
        landmarksVisited = landmarkMemory.all().count { it.visited },
        interiorMapsExplored = interiorMemory.knownMapIds().size,
    )

    private fun result(outcome: CampaignOutcome, runs: Int, cost: Double) =
        CampaignResult(outcome, runs, snapshotCoverage(), cost)

    private fun elapsedMin(startMs: Long) = (System.currentTimeMillis() - startMs) / 60_000.0

    companion object {
        /** Frames to advance with no input held after each loadState. Bridges the
         *  V5.2 "input not responding" quirk where the emulator drops input frames
         *  during the first ~0.3s of resumed emulation. 30 frames ≈ 0.5s @ 60Hz. */
        const val WARMUP_FRAMES = 30

        fun goalsAchieved(landmarkMemory: LandmarkMemory): Boolean {
            val king = landmarkMemory.findByKind(LandmarkKind.NPC_KING).any { it.visited }
            val shop = landmarkMemory.findByKind(LandmarkKind.NPC_SHOPKEEPER).any { it.visited }
            return king && shop
        }
    }
}
