package knes.agent.v2.runtime

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val createdAtTurn: Int,
    val milestone: String,
    val steps: List<PlanStep>,
    var cursor: Int = 0,        // index of current step
)

@Serializable
data class PlanStep(
    val index: Int,
    val description: String,
    val intentTool: String? = null,   // optional hint, e.g. "walkTo"
    val intentArgs: Map<String, String>? = null,
)
