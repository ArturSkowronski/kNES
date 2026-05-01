package knes.api

import kotlinx.serialization.Serializable

@Serializable
data class StepRequest(
    val buttons: List<String> = emptyList(),
    val frames: Int = 1,
    val screenshot: Boolean = false,
)
