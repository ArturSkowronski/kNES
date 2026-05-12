package knes.agent.v2.runtime

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val createdAtTurn: Int,
    val milestone: String,
    val steps: List<PlanStep>,
    var cursor: Int = 0,
)

@Serializable
data class PlanStep(
    val index: Int,
    val description: String,
    val intentTool: String? = null,
    val intentArgs: Map<String, String>? = null,
)
