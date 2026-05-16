package knes.agent.runtime

import kotlinx.serialization.Serializable

@Serializable
data class TurnLog(
    val turn: Int,
    val frame: Long,
    val phase: String,
    val ram: Map<String, Int>,
    val snapshot: String,
    val executor: ExecutorTrace,
    val watchdog: WatchdogTrace,
)

@Serializable
data class ExecutorTrace(
    val model: String,
    val tool: String,
    val args: Map<String, String>,
    val reasoningSummary: String,
    val outcome: String,
    val message: String? = null,
    val ms: Long,
)

@Serializable
data class WatchdogTrace(
    val stuckCounter: Int,
    val threshold: Int,
)
