package knes.agent.tools.results

import kotlinx.serialization.Serializable

@Serializable
data class StatusResult(val ok: Boolean, val message: String = "")

@Serializable
data class StepEntry(val buttons: List<String>, val frames: Int)

@Serializable
data class StepResult(
    val frame: Int,
    val ram: Map<String, Int>,
    val heldButtons: List<String>,
    /** Base64-encoded PNG, present iff the caller requested a screenshot. */
    val screenshot: String? = null,
)

@Serializable
data class StateSnapshot(
    val frame: Int,
    val ram: Map<String, Int>,
    val cpu: Map<String, Int>,
    val heldButtons: List<String>,
)

@Serializable
data class ScreenPng(val base64: String, val width: Int = 256, val height: Int = 240)

@Serializable
data class ProfileSummary(val id: String, val name: String, val description: String)

@Serializable
data class ActionDescriptor(
    val id: String,
    val profileId: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class ActionToolResult(val ok: Boolean, val message: String, val data: Map<String, String> = emptyMap())
