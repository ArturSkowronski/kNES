package knes.agent.v2.runtime

import kotlinx.serialization.Serializable

@Serializable
data class Campaign(
    val startedAt: String,
    val scope: String,
    val milestones: MutableList<Milestone> = mutableListOf(),
    val plans: MutableList<PlanEntry> = mutableListOf(),
    val reviews: MutableList<ReviewEntry> = mutableListOf(),
    var lastTurn: Int = 0,
    var done: Boolean = false,
)

@Serializable
data class Milestone(
    val id: String,
    var status: String,        // "pending" | "in_progress" | "done"
    var turnStart: Int? = null,
    var turnEnd: Int? = null,
    var planStep: Int? = null,
)

@Serializable
data class PlanEntry(
    val turn: Int,
    val by: String,             // "advisor"
    val summary: String,
    val snapshot: String,       // relative path
    val reason: String? = null, // "stuck-signal" | "T0 fresh" | ...
)

@Serializable
data class ReviewEntry(
    val turn: Int,
    val removed: List<String>,
    val flagged: List<String>,
)
