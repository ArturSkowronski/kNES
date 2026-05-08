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

    /** Goal-aware exploration. Task 9 fills the loop body. */
    suspend fun exploreUntilFound(
        goal: LandmarkKind,
        predicate: (Landmark) -> Boolean,
        capSteps: Int,
    ): ExploreOutcome {
        TODO("implemented in Task 9")
    }
}
