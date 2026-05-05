package knes.agent.explorer

import knes.agent.perception.Landmark

/**
 * Cheap, focused LLM consult fired only on novel triggers (new interior, dialog,
 * battle). Implementations may use Anthropic Haiku 4.5 or a fake for tests.
 *
 * Each call returns a [Result] with the cost in USD so the campaign budget can
 * track cumulative spend.
 */
interface HaikuConsult {
    data class InteriorClassification(
        val landmarks: List<Landmark>,
        val costUsd: Double,
    )

    data class DialogReading(
        val summary: String,
        val landmarkHint: String?,   // e.g. "King", "Shopkeeper", or null
        val costUsd: Double,
    )

    /** Called after [knes.agent.skills.ExploreInteriorFrontier] finishes a fresh interior.
     *  Implementation should look at screenshot + visited tile count and return any
     *  Landmark records to add (NPC_KING / NPC_SHOPKEEPER / NPC_GENERIC / etc.). */
    suspend fun classifyInterior(
        mapId: Int,
        visitedTileCount: Int,
        screenshotPng: ByteArray?,
    ): InteriorClassification

    /** Called when a dialog box is open. Implementation should read the dialog text
     *  (may press A across pages) and return a summary plus optional landmark hint. */
    suspend fun readDialog(
        screenshotPng: ByteArray?,
    ): DialogReading
}

/** Test fake. Pass canned results in constructor; assert calls via `interiorCalls`/`dialogCalls`. */
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set

    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotPng: ByteArray?,
    ): HaikuConsult.InteriorClassification {
        val res = interiorClassifications.getOrNull(interiorCalls)
            ?: HaikuConsult.InteriorClassification(emptyList(), 0.0)
        interiorCalls++
        return res
    }

    override suspend fun readDialog(screenshotPng: ByteArray?): HaikuConsult.DialogReading {
        val res = dialogReadings.getOrNull(dialogCalls)
            ?: HaikuConsult.DialogReading("", null, 0.0)
        dialogCalls++
        return res
    }
}
