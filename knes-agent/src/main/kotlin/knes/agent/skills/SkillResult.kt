package knes.agent.skills

import kotlinx.serialization.Serializable

@Serializable
data class SkillResult(
    val ok: Boolean,
    val message: String,
    val framesElapsed: Int = 0,
    val ramAfter: Map<String, Int> = emptyMap(),
)
