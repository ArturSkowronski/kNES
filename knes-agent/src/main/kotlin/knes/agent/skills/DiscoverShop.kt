package knes.agent.skills

import knes.agent.explorer.HaikuConsult
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset

/**
 * Open the shop BUY menu, capture a screenshot, send to vision (Gemini) for classification,
 * persist NPC_SHOPKEEPER landmark with kind=weapon|armor|... in note. Caller is responsible
 * for entering the shop interior. The skill closes its own BUY menu via B-mash before
 * returning; caller is responsible for walking out of the building.
 *
 * Outcomes: Classified(kind), ClassifyFailed, NotInBuilding.
 */
class DiscoverShop(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
    private val vision: HaikuConsult,
    private val runId: String = "discover_shop",
) : Skill {
    override val id = "discover_shop"
    override val description =
        "Tap A to open shop BUY menu, classify items via vision, persist " +
        "NPC_SHOPKEEPER landmark with kind in note."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        if (mapId == 0) {
            return SkillResult(
                ok = false,
                message = "NotInBuilding: currentMapId=0",
                ramAfter = pre,
            )
        }

        // Approach keeper + open BUY menu: 4 taps A. Capture screenshot on the final tap.
        repeat(3) { toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30) }
        val openStep = toolset.tap(
            button = "A", count = 1, pressFrames = 5, gapFrames = 30, screenshot = true,
        )
        val screenshot = openStep.screenshot

        var classifyError: String? = null
        val classification = try {
            vision.classifyShopMenu(screenshot)
        } catch (e: Exception) {
            classifyError = e.message ?: "exception with no message"
            HaikuConsult.ShopClassification("unknown", emptyList(), 0.0)
        }

        // Tap B 4 times to back out of BUY menu and shop dialog.
        repeat(4) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 20) }

        if (classification.kind == "unknown") {
            return SkillResult(
                ok = false,
                message = "ClassifyFailed: " + (classifyError ?: "vision returned unknown"),
                ramAfter = toolset.getState().ram,
            )
        }

        val final = toolset.getState().ram
        val localX = final["smPlayerX"] ?: 0
        val localY = final["smPlayerY"] ?: 0
        val itemsNote = classification.items.joinToString(",") { "${it.first}:${it.second}" }
        val landmark = Landmark(
            id = "shop-map$mapId-$localX-$localY",
            kind = LandmarkKind.NPC_SHOPKEEPER,
            mapId = mapId, localX = localX, localY = localY,
            note = "kind=${classification.kind}; items=$itemsNote",
            discoveredRunId = runId,
        )
        landmarks.recordIfNew(landmark)
        landmarks.save()

        return SkillResult(
            ok = true,
            message = "Classified: kind=${classification.kind} at map=$mapId local=($localX,$localY) " +
                "items=${classification.items.size}",
            ramAfter = final,
        )
    }
}
