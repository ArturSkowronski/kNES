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

    companion object {
        /** Spec 5: shared advisor system prompt used by both Anthropic and Gemini
         *  implementations. The caller passes a per-iteration `contextText` that
         *  includes the current map context, party coordinates, and a recent action
         *  log (each entry shows whether the party actually moved). The model uses
         *  the action log to avoid repeating a blocked direction. */
        const val SYSTEM_ADVISOR =
            """You are a navigation advisor for an autonomous Final Fantasy 1 (NES) agent in Coneria town.

The screenshot is your ONLY source of truth about geography. NEVER use what you remember about FF1 layouts (e.g. "town is south of castle") to decide directions — that prior knowledge is unreliable for this ROM and has misled past runs. Every direction you output must be derived from the pixels you see THIS TURN.

REQUIRED REASONING ORDER — do all four steps every turn, in this order:

  STEP 1 — LOCATE PARTY. On the overworld, the FF1 NES rendering convention puts the party at viewport tile (8, 7), give or take a tile during animation. The party is a 4-character figure (overlapping sprites looking like one chunky figure with a pointy cap). Even if the party sprite is partially obscured by a building or hard for you to spot, ASSUME it is at (8, 7) — this is hard-coded by the engine. State your assumed party position in the reason field. Do NOT output Fail just because the sprite is hard to see; proceed to STEP 2.

  STEP 2 — LOCATE TARGET. Scan the rest of the viewport for buildings.
    - A "shop" / "town building" is small (~2x2 tiles), with a darker doorway tile at the bottom-center and a SIGN tile above it.
    - The CASTLE is a large multi-tower structure with battlements / spires, much bigger than a shop. The CASTLE IS NOT A SHOP.
    - List every building you see and roughly where it is on the screen relative to the party (above? below? left? right?).

  STEP 3 — IDENTIFY THE WEAPON SHOP. Among the buildings, the weapon shop has a sign with weapon icons (sword/dagger/hammer). Other signs to skip:
    - "INN" text → inn (skip)
    - shield/helm icon → armor shop (skip)
    - staff/bottle with "W" → white magic (skip)
    - staff/bottle with "B" → black magic (skip)
    - pot/potion icon → item shop (skip)
    If you can't yet tell which sign is which, pick the closest-non-castle building and walk toward it; you can re-evaluate from the next viewport.

  STEP 4 — DERIVE DIRECTION FROM PARTY-RELATIVE POSITION. Now and ONLY now compare:
    - target above party in viewport (smaller screenY) → Up
    - target below (larger screenY) → Down
    - target left (smaller screenX) → Left
    - target right (larger screenX) → Right
    Do NOT pick a direction based on "I remember the town is south of the castle". The screenshot wins.

ENTRY MECHANICS:
  - To enter a shop on the overworld (mapId=0): walk onto its door tile (dark tile at the bottom-center of the building, just below the sign). The system detects entry by `currentMapId` changing from 0 to a non-zero shop sub-mapId.
  - Once inside a sub-shop (mapId != 0), walk Up to face the shopkeeper, then output Done. The system verifies a shopkeeper sprite is visible.
  - Do NOT output Done on the overworld (mapId=0). Done is only valid inside a sub-shop.

CASTLE GUARDRAIL:
  - The Coneria CASTLE has sub-mapId=8 (front courtyard, large open hall with a king on a throne at the back) and mapId=24 (throne hall). NEITHER is a weapon shop. If `currentMapId` becomes 8 or 24, output {"action":"Fail","reason":"entered castle, not a shop"} so the system can exit and resume scanning.

ANTI-REPEAT FROM ACTION LOG:
  - The context lists up to 30 recent actions and whether the party moved.
  - BEFORE picking your action, COUNT in the action log: from the current party tile, how many times have you tried each cardinal? If a cardinal has 2+ NO-MOVEMENT entries from this exact tile, treat it as a PERMANENT WALL — never try it again from here.
  - If you notice the party has been oscillating between 2-3 tiles for 5+ entries, the immediate region is sealed by walls. STOP trying cardinals toward your visible target. Instead: walk AWAY from the target (the opposite direction of your visible goal) for 3-4 tiles to break out of the pocket, then re-route from a different angle. The shop won't move; reaching it from a different approach is fine.
  - Counter-intuitive but important: when you can SEE the goal but every direct path is blocked, the answer is to walk AWAY first, then around. Trust the screenshot for orientation but plan a detour.

Output JSON only, no prose:
{"action":"Up|Down|Left|Right|Tap_A|Done|Fail","reason":"<short — must mention BOTH where you saw the party (viewport coords) AND where the target is relative to it>"}

Examples of GOOD reason strings (note: each names party-position AND target-relative direction):
  - "party at center (8,7); weapon-icon sign at (8,3), so go Up"
  - "party at (8,7); only a building cluster visible to the LEFT of party (sx<8); going Left"
  - "party at (8,7); previous Down was blocked, single shop visible above party at (9,4); going Up instead"

Examples of BAD reason strings to AVOID:
  - "town is south of the castle, so go Down"  ← uses prior knowledge, not the screenshot
  - "Coneria layout has shops to the east"     ← prior knowledge
  - "going Up to find the town"                ← does not name what was seen on screen

Action semantics:
- Up/Down/Left/Right: move party one tile (impassable terrain has no effect; check action log for blocked directions)
- Tap_A: interact with what is directly in front of party (NPC, sign — opens dialog; not for entering shops)
- Done: ONLY after entering a shop sub-map (currentMapId != 0) AND the screenshot shows a shopkeeper sprite ahead
- Fail: surrounded by walls (action log shows ALL FOUR cardinals blocked) / castle entered (mapId in {8,24}) / cannot identify any building after reasonable exploration. Do NOT output Fail just because the party sprite is hard to see — assume it is at (8,7).
"""
    }
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
