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

    sealed interface OverworldClassification {
        /** screenX/Y are tile coordinates within the visible 16x16 viewport, where
         *  (0,0) is the top-left tile and (15,15) is the bottom-right. The caller
         *  converts to world coords via [knes.agent.perception.ViewportMap.localToWorld]. */
        data class Found(val screenX: Int, val screenY: Int, val costUsd: Double) : OverworldClassification
        data class NotFound(val costUsd: Double) : OverworldClassification
    }

    data class CandidateLandmark(
        /** "shopkeeper" | "king" | "innkeeper" | "generic_npc" | "stairs_up" |
         *  "stairs_down" | "chest" | "sign" | "exit_tile" */
        val kind: String,
        val screenX: Int,    // 0..15
        val screenY: Int,    // 0..14
        val confidence: Double,
    )

    data class CandidatesScan(
        val candidates: List<CandidateLandmark>,
        val costUsd: Double,
    )

    sealed interface VerifyResult {
        /** refinedShopKind is null for non-shopkeeper kinds. */
        data class Confirmed(
            val refinedKind: String,
            val refinedShopKind: String?,
            val reason: String,
            val costUsd: Double,
        ) : VerifyResult
        data class Rejected(val reason: String, val costUsd: Double) : VerifyResult
        data class Errored(val reason: String, val costUsd: Double) : VerifyResult
    }

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

    /** Called when the agent needs to locate a known FF1 overworld landmark
     *  (e.g. the Chaos Shrine / Temple of Fiends) in the current viewport.
     *  `kind` is a free-form descriptor like "chaos_shrine" that the prompt
     *  template translates into pixel-art guidance. Returns `NotFound` on any
     *  failure (no exception leaks). */
    suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): OverworldClassification

    /** Pass 1: enumerate visible interior landmarks. Returns empty list on any
     *  failure (no exception leaks). */
    suspend fun scanInteriorCandidates(
        screenshotBase64: String?,
    ): CandidatesScan

    /** Pass 2: verify a single candidate against a focused crop. Returns
     *  [VerifyResult.Errored] on any infrastructure failure (no exception leaks). */
    suspend fun verifyLandmark(
        focusedScreenshotBase64: String?,
        candidateKind: String,
        candidateScreenX: Int,
        candidateScreenY: Int,
    ): VerifyResult

    /** Spec 5: Opus advisor for one-step navigation toward a goal. */
    data class AdviceResponse(
        /** "Up" | "Down" | "Left" | "Right" | "Tap_A" | "Done" | "Fail" */
        val action: String,
        val reason: String,
        val costUsd: Double,
    )

    suspend fun adviseShopApproach(
        screenshotBase64: String?,
        contextText: String,
    ): AdviceResponse
}

/** Test fake. Pass canned results in constructor; assert calls via `interiorCalls`/`dialogCalls`/`shopCalls`. */
class FakeHaikuConsult(
    private val interiorClassifications: List<HaikuConsult.InteriorClassification> = emptyList(),
    private val dialogReadings: List<HaikuConsult.DialogReading> = emptyList(),
    private val shopClassifications: List<HaikuConsult.ShopClassification> = emptyList(),
    private val overworldClassifications: List<HaikuConsult.OverworldClassification> = emptyList(),
    private val candidatesScans: List<HaikuConsult.CandidatesScan> = emptyList(),
    private val verifyResults: List<HaikuConsult.VerifyResult> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set
    var shopCalls: Int = 0; private set
    var overworldCalls: Int = 0; private set
    var scanCalls: Int = 0; private set
    var verifyCalls: Int = 0; private set
    val verifyArgs: MutableList<Triple<String, Int, Int>> = mutableListOf()

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

    override suspend fun classifyOverworldLandmark(
        screenshotBase64: String?,
        kind: String,
    ): HaikuConsult.OverworldClassification {
        val res = overworldClassifications.getOrNull(overworldCalls)
            ?: HaikuConsult.OverworldClassification.NotFound(0.0)
        overworldCalls++
        return res
    }

    override suspend fun scanInteriorCandidates(
        screenshotBase64: String?,
    ): HaikuConsult.CandidatesScan {
        val res = candidatesScans.getOrNull(scanCalls)
            ?: HaikuConsult.CandidatesScan(emptyList(), 0.0)
        scanCalls++
        return res
    }

    override suspend fun verifyLandmark(
        focusedScreenshotBase64: String?,
        candidateKind: String,
        candidateScreenX: Int,
        candidateScreenY: Int,
    ): HaikuConsult.VerifyResult {
        verifyArgs.add(Triple(candidateKind, candidateScreenX, candidateScreenY))
        val res = verifyResults.getOrNull(verifyCalls)
            ?: HaikuConsult.VerifyResult.Errored("fake-not-scripted", 0.0)
        verifyCalls++
        return res
    }

    override suspend fun adviseShopApproach(
        screenshotBase64: String?,
        contextText: String,
    ): HaikuConsult.AdviceResponse {
        return HaikuConsult.AdviceResponse("Fail", "fake-not-scripted", 0.0)
    }
}
