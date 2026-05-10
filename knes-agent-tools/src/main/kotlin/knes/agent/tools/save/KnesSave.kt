package knes.agent.tools.save

import kotlinx.serialization.Serializable

@Serializable
data class KnesSave(
    val schemaVersion: Int = 1,
    val createdAtMs: Long,
    val rom: String,
    val emulatorState: String,
    val currentIntent: String,
    val recentMoves: List<MoveEntry> = emptyList(),
    val decisionLog: List<DecisionEntry> = emptyList(),
    val landmarks: LandmarksSnapshot = LandmarksSnapshot(),
    val visitedMinimap: VisitedMinimap = VisitedMinimap(bitsBase64 = ""),
)

@Serializable
data class MoveEntry(
    val seq: Int,
    val tMs: Long,
    val dir: String,
    val smPre: List<Int>? = null,
    val smPost: List<Int>? = null,
    val moved: Boolean? = null,
    val mapflagsPost: Int? = null,
    val note: String? = null,
)

@Serializable
data class DecisionEntry(
    val seq: Int,
    val tMs: Long,
    val phase: String,
    val reasoning: String,
    val action: String,
    val outcome: String? = null,
)

@Serializable
data class LandmarksSnapshot(
    val kings: List<LandmarkRef> = emptyList(),
    val shops: List<LandmarkRef> = emptyList(),
    val inns: List<LandmarkRef> = emptyList(),
    val bridges: List<LandmarkRef> = emptyList(),
    val other: List<LandmarkRef> = emptyList(),
)

@Serializable
data class LandmarkRef(
    val mapId: Int,
    val x: Int,
    val y: Int,
    val label: String,
)

@Serializable
data class VisitedMinimap(
    val width: Int = 32,
    val height: Int = 32,
    val bitsBase64: String,
)
