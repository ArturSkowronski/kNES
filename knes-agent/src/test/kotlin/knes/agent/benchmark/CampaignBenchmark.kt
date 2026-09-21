package knes.agent.benchmark

import knes.agent.campaign.Campaign
import knes.agent.runtime.Phase
import knes.agent.tools.results.GameSemantics
import knes.api.EmulatorSession
import knes.api.Replay

/**
 * Plays an input script and reports which campaign milestones it reached.
 *
 * The point is a win condition that can be checked **without a model**. Milestone
 * predicates already exist and the Reviewer already uses them; this runs them against a
 * deterministic replay, so "the agent got to Coneria" becomes something CI can decide
 * rather than something a transcript has to be read for.
 *
 * Milestones latch, exactly as they do in a live run: a predicate describing a transient
 * state (standing on a shop tile) would otherwise be missed between samples.
 */
class CampaignBenchmark(
    private val campaign: Campaign,
    private val semantics: GameSemantics,
    private val milestones: List<String>,
) {
    fun run(session: EmulatorSession, replay: Replay): BenchmarkResult {
        val reached = linkedSetOf<String>()

        for (entry in replay.entries) {
            session.controller.setButtons(entry.buttons)
            repeat(entry.frames) {
                session.advanceFrames(1)
                session.controller.onFrameBoundary()
                evaluate(session, reached)
            }
        }
        session.controller.releaseAll()

        val ram = session.getWatchedState()
        return BenchmarkResult(
            reached = reached.toList(),
            missed = milestones.filterNot { it in reached },
            frames = session.frameCount,
            finalPhase = Phase.of(semantics.phase(ram)),
            gold = campaign.gold(ram),
        )
    }

    /** Evaluated every frame: a transient milestone is gone by the next one. */
    private fun evaluate(session: EmulatorSession, reached: MutableSet<String>) {
        val ram = session.getWatchedState()
        val phase = Phase.of(semantics.phase(ram))
        val prereqDone = milestones.associateWith { it in reached }

        for (milestone in milestones) {
            if (milestone in reached) continue
            if (campaign.isSatisfied(milestone, phase, ram, prereqDone)) {
                reached += milestone
            }
        }
    }
}

data class BenchmarkResult(
    val reached: List<String>,
    val missed: List<String>,
    val frames: Int,
    val finalPhase: Phase,
    val gold: Int,
) {
    override fun toString(): String =
        "reached=${reached.joinToString(",").ifEmpty { "(none)" }} " +
            "missed=${missed.joinToString(",").ifEmpty { "(none)" }} " +
            "frames=$frames phase=$finalPhase gold=$gold"
}
