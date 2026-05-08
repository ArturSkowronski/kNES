package knes.agent.runtime

import knes.agent.perception.FrameChangeDetector
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.skills.InteriorScanner

/**
 * Adapter for the emulator state needed by InteriorExplorer.
 *
 * Concrete impl in Task 10 wraps the real toolset/RAM reader.
 * Test stubs implement directly.
 */
interface InteriorEmulatorState {
    /** Returns null when OAM API is unavailable; explorer falls back to pixel hash. */
    fun captureOam(): Set<FrameChangeDetector.SpriteSlot>?
    fun capturePixels(): ByteArray   // raw 256x240 byte buffer; empty array if unavailable
    fun captureScreenshotBase64(): String?
    fun captureFocusedCropBase64(screenTileX: Int, screenTileY: Int): String?
    fun currentMapId(): Int
    fun partyLocalX(): Int
    fun partyLocalY(): Int
}

sealed interface WalkOutcome {
    data class Stepped(val direction: String) : WalkOutcome
    data object Stuck : WalkOutcome
    data object EncounterStarted : WalkOutcome
}

/**
 * Adapter for the existing WalkInteriorVision skill.
 *
 * The existing skill is UNCHANGED per spec §1; this adapter maps its outcome
 * to [WalkOutcome] without leaking the existing API into the explorer.
 */
interface WalkInteriorVisionAdapter {
    suspend fun step(): WalkOutcome
}

class InteriorExplorer(
    private val walk: WalkInteriorVisionAdapter,
    private val scanner: InteriorScanner,
    private val frameDetector: FrameChangeDetector,
    private val emu: InteriorEmulatorState,
    private val memory: LandmarkMemory,
) {
    sealed interface ExploreOutcome {
        data class Found(val landmark: Landmark, val stats: ExploreStats) : ExploreOutcome
        data class NotFoundCapReached(val stats: ExploreStats) : ExploreOutcome
        data class StuckBailout(val reason: String, val stats: ExploreStats) : ExploreOutcome
        data class EncounterTriggered(val stats: ExploreStats) : ExploreOutcome
    }

    data class ExploreStats(
        val walkSteps: Int,
        val scansTriggered: Int,
        val candidatesEvaluated: Int,
        val confirmed: Int,
        val rejected: Int,
        val errored: Int,
        val costUsd: Double,
    )

    /** Goal-aware exploration. */
    suspend fun exploreUntilFound(
        goal: LandmarkKind,
        predicate: (Landmark) -> Boolean,
        capSteps: Int,
    ): ExploreOutcome {
        var walkSteps = 0
        var scansTriggered = 0
        var candidatesEvaluated = 0
        var confirmedCount = 0
        var rejectedCount = 0
        var erroredCount = 0
        var costUsd = 0.0
        var consecutiveStuck = 0
        var consecutiveScanEmpty = 0

        fun stats() = ExploreStats(
            walkSteps, scansTriggered, candidatesEvaluated,
            confirmedCount, rejectedCount, erroredCount, costUsd,
        )

        while (walkSteps < capSteps) {
            val pixels = emu.capturePixels()
            val oam = emu.captureOam()

            if (frameDetector.shouldScan(oam, pixels)) {
                scansTriggered++
                val screenshot = emu.captureScreenshotBase64()
                val scan = scanner.scanCandidates(screenshot)
                costUsd += scan.costUsd
                if (scan.candidates.isEmpty()) {
                    consecutiveScanEmpty++
                    if (consecutiveScanEmpty >= 3) {
                        return ExploreOutcome.StuckBailout("pass1-degraded", stats())
                    }
                } else {
                    consecutiveScanEmpty = 0
                    for (cand in scan.candidates) {
                        candidatesEvaluated++
                        val focused = emu.captureFocusedCropBase64(cand.screenX, cand.screenY)
                        val pr = scanner.verifyAndPersist(
                            focused,
                            cand,
                            mapId = emu.currentMapId(),
                            partyLocalX = emu.partyLocalX(),
                            partyLocalY = emu.partyLocalY(),
                        )
                        when (pr) {
                            is InteriorScanner.PersistResult.Confirmed -> {
                                confirmedCount++
                                costUsd += pr.costUsd
                            }
                            is InteriorScanner.PersistResult.Rejected -> {
                                rejectedCount++
                                costUsd += pr.costUsd
                            }
                            is InteriorScanner.PersistResult.Errored -> {
                                erroredCount++
                                costUsd += pr.costUsd
                            }
                        }
                    }
                }
            }

            // Goal check after scan — landmark may have just been persisted.
            val foundLandmark = memory.findByKind(goal).firstOrNull(predicate)
            if (foundLandmark != null) {
                return ExploreOutcome.Found(foundLandmark, stats())
            }

            // Walk step.
            val walkOutcome = walk.step()
            walkSteps++
            when (walkOutcome) {
                is WalkOutcome.Stepped -> consecutiveStuck = 0
                is WalkOutcome.Stuck -> {
                    consecutiveStuck++
                    if (consecutiveStuck >= 3) {
                        return ExploreOutcome.StuckBailout(
                            "walk-stuck-after-${walkSteps}-steps", stats(),
                        )
                    }
                }
                is WalkOutcome.EncounterStarted ->
                    return ExploreOutcome.EncounterTriggered(stats())
            }
        }
        return ExploreOutcome.NotFoundCapReached(stats())
    }
}
