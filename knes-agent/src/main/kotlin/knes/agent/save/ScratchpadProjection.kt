package knes.agent.save

import knes.agent.runtime.AgentScratchpad
import knes.agent.runtime.ScratchpadEntry
import knes.agent.tools.save.DecisionEntry
import knes.agent.tools.save.MoveEntry

/**
 * Convention: a "decision" entry stores reasoning/action/outcome inside the
 * existing summary/note fields with this delimiter, so the scratchpad schema
 * stays unchanged.
 *
 *   summary = "<phase>: <action> — <reasoning>"
 *   note    = outcome (nullable)
 */
private const val DECISION_DELIM = " — "

fun AgentScratchpad.recordDecision(
    phase: String,
    reasoning: String,
    action: String,
    outcome: String? = null,
) {
    record(
        kind = "decision",
        summary = "$phase: $action$DECISION_DELIM$reasoning",
        note = outcome,
    )
}

fun AgentScratchpad.recentMoves(n: Int = 8): List<MoveEntry> =
    all().asReversed()
        .filter { it.kind == "tap" || it.kind == "walk" }
        .take(n)
        .reversed()
        .map { it.toMoveEntry() }

fun AgentScratchpad.recentDecisions(n: Int = 30): List<DecisionEntry> =
    all().asReversed()
        .filter { it.kind == "decision" }
        .take(n)
        .reversed()
        .map { it.toDecisionEntry() }

private fun ScratchpadEntry.toMoveEntry(): MoveEntry = MoveEntry(
    seq = seq, tMs = tMs,
    dir = dir ?: "",
    smPre = smPre, smPost = smPost,
    moved = moved,
    mapflagsPost = mapflagsPost,
    note = note,
)

private fun ScratchpadEntry.toDecisionEntry(): DecisionEntry {
    val (phaseAndAction, reasoning) = summary.split(DECISION_DELIM, limit = 2)
        .let { if (it.size == 2) it[0] to it[1] else it[0] to "" }
    val (phase, action) = phaseAndAction.split(": ", limit = 2)
        .let { if (it.size == 2) it[0] to it[1] else "" to phaseAndAction }
    return DecisionEntry(
        seq = seq, tMs = tMs,
        phase = phase, reasoning = reasoning, action = action,
        outcome = note,
    )
}
