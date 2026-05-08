package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory

/** Trace event sink. Same shape as runtime.InteriorTraceSink but defined here to avoid runtime↔skills coupling. */
typealias InteriorTraceSink = (String) -> Unit

/**
 * Two-pass vision scanner for interior landmark self-discovery.
 *
 * Pass 1: enumerate visible candidates (cheap LLM, e.g. Haiku 4.5).
 * Pass 2: verify each candidate against a focused crop and persist confirmed
 * landmarks via [LandmarkMemory].
 *
 * Stateless across calls. Persistence flows through [LandmarkMemory].
 */
class InteriorScanner(
    private val haiku: HaikuConsult,
    private val memory: LandmarkMemory,
    private val runId: String,
    private val confidenceThreshold: Double = 0.5,
    private val traceSink: InteriorTraceSink = {},
) {
    data class ScanResult(
        val candidates: List<HaikuConsult.CandidateLandmark>,
        val costUsd: Double,
    )

    sealed interface PersistResult {
        data class Confirmed(val landmark: Landmark, val costUsd: Double) : PersistResult
        data class Rejected(val reason: String, val costUsd: Double) : PersistResult
        data class Errored(val reason: String, val costUsd: Double) : PersistResult
    }

    /** Pass 1 — invoke Haiku, filter low-confidence candidates. */
    suspend fun scanCandidates(screenshotBase64: String?): ScanResult {
        val raw = haiku.scanInteriorCandidates(screenshotBase64)
        val filtered = raw.candidates.filter { it.confidence >= confidenceThreshold }
        return ScanResult(filtered, raw.costUsd)
    }

    /** Pass 2 — verify a single candidate and persist to landmarkMemory if confirmed. */
    suspend fun verifyAndPersist(
        focusedScreenshotBase64: String?,
        candidate: HaikuConsult.CandidateLandmark,
        mapId: Int,
        partyLocalX: Int,
        partyLocalY: Int,
    ): PersistResult {
        val verify = haiku.verifyLandmark(
            focusedScreenshotBase64,
            candidate.kind,
            candidate.screenX,
            candidate.screenY,
        )
        return when (verify) {
            is HaikuConsult.VerifyResult.Errored -> {
                traceSink(
                    "interior_scan_error: pass=2, candidate=${candidate.kind}, " +
                        "screenXY=(${candidate.screenX},${candidate.screenY}), " +
                        "reason=${verify.reason}",
                )
                PersistResult.Errored(verify.reason, verify.costUsd)
            }
            is HaikuConsult.VerifyResult.Rejected -> {
                traceSink(
                    "interior_scan_rejected: candidateKind=${candidate.kind}, " +
                        "reason=pass2-rejected, pass2Reason=${verify.reason}",
                )
                PersistResult.Rejected(verify.reason, verify.costUsd)
            }
            is HaikuConsult.VerifyResult.Confirmed -> {
                val (lx, ly) = screenTileToLocal(
                    candidate.screenX, candidate.screenY, partyLocalX, partyLocalY,
                ) ?: run {
                    traceSink(
                        "interior_scan_rejected: candidateKind=${candidate.kind}, " +
                            "reason=invalid-coords",
                    )
                    return PersistResult.Rejected("invalid-coords", verify.costUsd)
                }
                val kind = kindStringToEnum(verify.refinedKind)
                val note = if (verify.refinedShopKind != null) {
                    "kind=${verify.refinedShopKind}; verified=pass2; reason=${verify.reason}"
                } else {
                    "verified=pass2; reason=${verify.reason}"
                }
                val landmark = Landmark(
                    id = "interior_${kind.name.lowercase()}_${mapId}_${lx}_$ly",
                    kind = kind,
                    mapId = mapId,
                    localX = lx,
                    localY = ly,
                    visited = false,
                    note = note,
                    discoveredRunId = runId,
                )
                memory.recordIfNew(landmark)
                memory.save()
                traceSink(
                    "interior_scan_confirmed: kind=${landmark.kind}, " +
                        "mapId=${landmark.mapId}, localXY=(${landmark.localX},${landmark.localY}), " +
                        "note=${landmark.note}, runId=${landmark.discoveredRunId}",
                )
                PersistResult.Confirmed(landmark, verify.costUsd)
            }
        }
    }

    private fun screenTileToLocal(
        screenX: Int, screenY: Int, partyLocalX: Int, partyLocalY: Int,
    ): Pair<Int, Int>? {
        if (screenX !in 0..15 || screenY !in 0..14) return null
        // FF1 party renders at viewport tile (8, 7).
        val dx = screenX - 8
        val dy = screenY - 7
        return Pair(partyLocalX + dx, partyLocalY + dy)
    }

    companion object {
        fun kindStringToEnum(kindStr: String): LandmarkKind = when (kindStr) {
            "shopkeeper" -> LandmarkKind.NPC_SHOPKEEPER
            "king" -> LandmarkKind.NPC_KING
            "innkeeper" -> LandmarkKind.NPC_INNKEEPER
            "generic_npc" -> LandmarkKind.NPC_GENERIC
            "stairs_up" -> LandmarkKind.STAIRS_UP
            "stairs_down" -> LandmarkKind.STAIRS_DOWN
            "exit_tile" -> LandmarkKind.EXIT_TILE
            "chest" -> LandmarkKind.CHEST
            "sign" -> LandmarkKind.SIGN
            else -> LandmarkKind.UNKNOWN
        }
    }
}
