package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.LandmarkMemory

/**
 * Two-pass vision scanner for interior landmark self-discovery.
 *
 * Pass 1: enumerate visible candidates (cheap LLM, e.g. Haiku 4.5).
 * Pass 2 (Task 7): verify each candidate against a focused crop (Gemini Pro).
 *
 * Stateless across calls. Persistence flows through [LandmarkMemory].
 */
class InteriorScanner(
    private val haiku: HaikuConsult,
    private val memory: LandmarkMemory,
    private val runId: String,
    private val confidenceThreshold: Double = 0.5,
) {
    data class ScanResult(
        val candidates: List<HaikuConsult.CandidateLandmark>,
        val costUsd: Double,
    )

    /** Pass 1 — invoke Haiku, filter low-confidence candidates. */
    suspend fun scanCandidates(screenshotBase64: String?): ScanResult {
        val raw = haiku.scanInteriorCandidates(screenshotBase64)
        val filtered = raw.candidates.filter { it.confidence >= confidenceThreshold }
        return ScanResult(filtered, raw.costUsd)
    }
}
