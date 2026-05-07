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

    data class ShopClassification(
        /** "weapon" | "armor" | "whiteMagic" | "blackMagic" | "item" | "unknown" */
        val kind: String,
        /** Each item has fields name (string) and price (int). May be empty on unknown. */
        val items: List<Pair<String, Int>>,
        val costUsd: Double,
    )

    /** Called after [knes.agent.skills.ExploreInteriorFrontier] finishes a fresh interior.
     *  Implementation should look at screenshot + visited tile count and return any
     *  Landmark records to add (NPC_KING / NPC_SHOPKEEPER / NPC_GENERIC / etc.). */
    suspend fun classifyInterior(
        mapId: Int,
        visitedTileCount: Int,
        screenshotBase64: String?,
        runId: String = "",
    ): InteriorClassification

    /** Called when a dialog box is open. Implementation should read the dialog text
     *  (may press A across pages) and return a summary plus optional landmark hint. */
    suspend fun readDialog(
        screenshotBase64: String?,
    ): DialogReading

    /** Called when an FF1 shop BUY menu is open. Implementation reads item names + prices
     *  from the screenshot and classifies the shop. Returns kind="unknown" on any failure. */
    suspend fun classifyShopMenu(
        screenshotBase64: String?,
    ): ShopClassification
}

/** Test fake. Pass canned results in constructor; assert calls via `interiorCalls`/`dialogCalls`/`shopCalls`. */
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
    private val shopClassifications: List<HaikuConsult.ShopClassification> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set
    var shopCalls: Int = 0; private set

    override suspend fun classifyInterior(
        mapId: Int, visitedTileCount: Int, screenshotBase64: String?, runId: String,
    ): HaikuConsult.InteriorClassification {
        val res = interiorClassifications.getOrNull(interiorCalls)
            ?: HaikuConsult.InteriorClassification(emptyList(), 0.0)
        interiorCalls++
        return res
    }

    override suspend fun readDialog(screenshotBase64: String?): HaikuConsult.DialogReading {
        val res = dialogReadings.getOrNull(dialogCalls)
            ?: HaikuConsult.DialogReading("", null, 0.0)
        dialogCalls++
        return res
    }

    override suspend fun classifyShopMenu(screenshotBase64: String?): HaikuConsult.ShopClassification {
        val res = shopClassifications.getOrNull(shopCalls)
            ?: HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        shopCalls++
        return res
    }
}
