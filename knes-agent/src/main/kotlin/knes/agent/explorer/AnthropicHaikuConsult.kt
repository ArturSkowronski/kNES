package knes.agent.explorer

import knes.agent.llm.AnthropicSession

/**
 * Production HaikuConsult backed by Anthropic Haiku 4.5. Phase 1 MVP stub:
 * returns no classifications and zero cost. Phase 1.5 will wire real prompts
 * + screenshot analysis. The campaign can run end-to-end with this stub —
 * terrain/warps/blockages still accumulate; only NPC landmark classification
 * is no-op until the prompt is added.
 */
class AnthropicHaikuConsult(
    private val anthropic: AnthropicSession,
) : HaikuConsult {
    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotPng: ByteArray?,
    ): HaikuConsult.InteriorClassification =
        HaikuConsult.InteriorClassification(landmarks = emptyList(), costUsd = 0.0)

    override suspend fun readDialog(screenshotPng: ByteArray?): HaikuConsult.DialogReading =
        HaikuConsult.DialogReading(summary = "", landmarkHint = null, costUsd = 0.0)
}
