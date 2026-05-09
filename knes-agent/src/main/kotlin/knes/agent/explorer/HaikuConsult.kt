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

    /** V5.44: which sub-screen of the FF1 NES shop dialog the screenshot shows.
     *  Used by [knes.agent.skills.BuyAtShop] state-machine purchase to dispatch
     *  the next tap based on observed UI rather than guessed tap counts. */
    enum class ShopMenuPhase {
        MAIN_MENU,    // BUY / SELL / EXIT (cursor on one of them)
        ITEM_LIST,    // list of items with prices (cursor on one row)
        FOR_WHOM,     // "for whom?" 4-character pick (cursor on a char)
        BUY_CONFIRM,  // "Buy for X gold? YES / NO"
        ANOTHER,      // post-purchase "another?" prompt (or returns to ITEM_LIST in some FF1 builds)
        WELCOME,      // initial Welcome dialog overlay (no menu cursor visible)
        CLOSED,       // no shop dialog at all — town overlay walking
        UNKNOWN,      // vision did not match any known phase
    }

    data class ShopMenuPhaseClassification(
        val phase: ShopMenuPhase,
        val costUsd: Double,
    )

    /** Returns which sub-screen of the FF1 NES shop dialog is currently drawn.
     *  Distinct from [classifyShopMenu] (which reports the shop kind / item list).
     *  Returns [ShopMenuPhase.UNKNOWN] on any infrastructure failure. */
    suspend fun classifyShopMenuPhase(
        screenshotBase64: String?,
    ): ShopMenuPhaseClassification

    /** V5.45: per-step shop purchase advisor. Replaces the brittle deterministic
     *  state-machine. Caller passes the current screenshot and a context block
     *  describing the goal (which chars need weapons, their classes, gold,
     *  recently-bought items, action log). Advisor returns the next single
     *  action to apply. Continues to be called until advisor returns Done or
     *  Fail. Returns [ShopPurchaseAdvice] with action="Fail" on any failure. */
    suspend fun adviseShopPurchase(
        screenshotBase64: String?,
        contextText: String,
    ): ShopPurchaseAdvice

    data class ShopPurchaseAdvice(
        /** "Up" | "Down" | "Left" | "Right" | "Tap_A" | "Tap_B" | "Done" | "Fail" */
        val action: String,
        val reason: String,
        val costUsd: Double,
    )

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

ENTRY MECHANICS (IMPORTANT — FF1 NES shops are NPC dialog overlays, NOT sub-maps):
  - On the OVERWORLD (mapflags.bit0=0, mapId=0): walk onto a town entry tile to drop into the town overlay layer.
  - Inside the TOWN OVERLAY (mapflags.bit0=1, mapId=0): walk between buildings, then walk ADJACENT to the shopkeeper sprite (one tile away, facing them) and press A to open the shop dialog — the BUY/SELL/EXIT menu (or the "Welcome" / "WEAPON" header) appears as an overlay. mapId STAYS 0 the entire time. This is normal — FF1 shops do NOT use sub-mapIds.
  - Output Done as soon as you can SEE the BUY/SELL/EXIT menu or the WEAPON shopkeeper dialog on screen. Do NOT wait for any mapId change — there will be none.
  - Some towns DO use sub-shop mapIds (mapflags.bit0=1, mapId>0). In that case, walk Up to face the keeper and Tap_A to open the menu, then Done.

CASTLE GUARDRAIL:
  - The Coneria CASTLE has sub-mapId=8 (front courtyard, large open hall with a king on a throne at the back) and mapId=24 (throne hall). NEITHER is a weapon shop. If `currentMapId` becomes 8 or 24, output {"action":"Fail","reason":"entered castle, not a shop"} so the system can exit and resume scanning.

ANTI-REPEAT FROM ACTION LOG (TOWN_OVERLAY caveat: NPCs MOVE):
  - The context lists up to 30 recent actions and whether the party moved.
  - In TOWN_OVERLAY regime, NPC sprites (shopkeepers, generic townspeople) WALK BETWEEN TILES each frame. A "no movement" entry in the action log might mean a real wall OR a transient NPC standing in that adjacent tile that has since walked away. DO NOT treat town-overlay no-movement entries as permanent walls on the first try.
  - In TOWN_OVERLAY: a direction needs 2+ no-movement entries from the SAME tile WITHIN THE LAST 5 ENTRIES (i.e. recently confirmed) before treating as permanent wall. If the last no-movement attempt is older than 5 entries, RE-TRY the direction — the NPC has likely moved.
  - In OVERWORLD or SUB_MAP: tiles are static; one no-movement attempt is enough to mark blocked.
  - If the party has been oscillating between 2-3 tiles for 5+ entries with no progress, the immediate region is sealed by walls (or stuck NPCs). STOP trying cardinals toward your visible target. Instead: walk AWAY from the target (the opposite direction of your visible goal) for 3-4 tiles to break out of the pocket, then re-route from a different angle.
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
- Done: when the BUY/SELL/EXIT menu (or WEAPON shopkeeper dialog) is visible on screen. mapId may be 0 (town overlay) or >0 (sub-shop) — both are valid; the system verifies via vision.
- Fail: surrounded by walls (action log shows ALL FOUR cardinals blocked) / castle entered (mapId in {8,24}) / cannot identify any building after reasonable exploration. Do NOT output Fail just because the party sprite is hard to see — assume it is at (8,7).
"""

        /** V5.45: in-shop purchase advisor system prompt. The advisor sees the
         *  current screenshot of the FF1 NES shop dialog and a per-iter context
         *  describing the goal + party state, and decides the next single tap. */
        const val SYSTEM_SHOP_PURCHASE = """You are a shop-purchase advisor for an autonomous Final Fantasy 1 (NES) agent INSIDE a weapon shop. Your job: read the screenshot, decide the next single tap to make progress toward the goal, and emit JSON.

THE FF1 NES WEAPON SHOP UI HAS THESE SUB-SCREENS — identify which one is on screen:

  WELCOME — only the keeper's "Welcome ..." text in a blue dialog box, no menu cursor on BUY/SELL/EXIT yet. → tap A to advance.

  MAIN_MENU — three rows visible: "Buy", "Sell", "Exit". A WHITE-PALM CURSOR (finger pointing right) is on ONE of them.
    - Cursor on "Buy"  → tap A to enter item list.
    - Cursor on "Sell" → tap Up to move to "Buy".
    - Cursor on "Exit" → tap Up twice to move to "Buy" (Up moves cursor up by 1 row, no wrap).

  ITEM_LIST — list of weapon names (with prices in gold on the right). Cursor on one row.
    - If the cursor is on the item you want (matches char's class) → tap A to select.
    - Otherwise → tap Down or Up to move toward the desired row.
    - To back out → tap B.

  FOR_WHOM — "for whom?" prompt with the 4 characters listed (top to bottom: char1, char2, char3, char4). Cursor on a character.
    - Move cursor with Up/Down to the target character.
    - Tap A to confirm.
    - Tap B to back to ITEM_LIST.

  BUY_CONFIRM — "Buy for X G?" with YES/NO. Cursor usually defaults to YES.
    - If you intended this purchase: tap A on YES.
    - To cancel: tap Down (cursor → NO), tap A — or just tap B.

  ANOTHER — post-purchase prompt (sometimes shows "another?" YES/NO, sometimes returns directly to ITEM_LIST).
    - To buy more: tap A (YES) or just stay in ITEM_LIST.
    - To finish: tap Down then A (NO), or tap B.

  CLOSED — no menu visible, party is back on town overlay (visible map tiles, NPCs walking around). The shop has closed.
    - Output Fail (caller must re-engage keeper).

CHARACTER CLASSES (FF1 NES, 0-indexed by class byte):
  0 = Fighter      (Knight after promotion)         — equips: Knife, Sword, Hammer, Axe, Rapier (NOT staff/nunchuck)
  1 = Thief        (Ninja after promotion)          — equips: Knife, Sword, Rapier (NOT hammer/staff/nunchuck)
  2 = Black Belt   (Master after promotion)         — equips: Nunchuck (mostly bare-handed; Knight only weapons rare)
  3 = Red Mage     (Red Wizard after promotion)     — equips: Knife, Rapier, Sword, Hammer, Staff (broad)
  4 = White Mage   (White Wizard after promotion)   — equips: Hammer, Staff (NOT swords/knives/rapier/nunchuck)
  5 = Black Mage   (Black Wizard after promotion)   — equips: Knife, Staff (NOT swords/hammers/rapier/nunchuck)

CONERIA WEAPON SHOP TYPICAL ITEMS (5 rows; observed in this ROM):
  Wooden Staff   (5G)  — Mage only (RM, WM, BM)
  Wooden Nunchuck(10G) — Black Belt only
  Small Knife    (5G)  — most non-mages can use (FT, TH, RM, BM)
  Rapier        (10G)  — light fighter (FT, TH, RM)
  Iron Hammer   (10G)  — FT, WM, RM

  Item names + prices may differ slightly in this ROM (translation). Read the
  screenshot for the actual names + prices.

GOAL: each turn, you receive a context block listing each char's class and whether they ALREADY have a weapon. Pick the cheapest class-compatible weapon for each char who needs one. Coordinate across chars (don't double-pick the same row if not needed).

OUTPUT (strict JSON only, no prose):
  {"action":"Up|Down|Left|Right|Tap_A|Tap_B|Done|Fail","reason":"<short — name the sub-screen you see, the cursor row, and why you're picking this action>"}

ACTION SEMANTICS:
  Up/Down       — move cursor one row in the active menu (rarely useful as horizontal action; cursor menus in FF1 shop are vertical)
  Tap_A         — confirm / advance
  Tap_B         — cancel / back one level
  Done          — declare ALL chars who can be served are served (or no more buys possible due to gold). System verifies via gold/inv check.
  Fail          — shop has CLOSED unexpectedly, or you cannot make progress.

GOOD reasons (each names sub-screen, cursor row, intention):
  - "MAIN_MENU cursor on Exit; Up to move toward Buy"
  - "ITEM_LIST cursor on Wooden Staff (row 0); char1=Fighter cannot use staff; Down to move to Small Knife (row 2)"
  - "BUY_CONFIRM cursor on YES; A to confirm purchase of Knife for char1"
  - "ANOTHER prompt cursor on YES; char2 still needs a weapon, A to continue buying"
  - "All 4 chars now have weapons (per context); Done"

BAD reasons to AVOID:
  - "I think I should buy something"  ← does not name what's on screen
  - "tap A" without naming sub-screen ← can confirm wrong action (e.g., A on Exit closes shop)
  - hallucinated item names ← read what's actually drawn
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
    private val shopMenuPhases: List<HaikuConsult.ShopMenuPhaseClassification> = emptyList(),
) : HaikuConsult {
    var interiorCalls: Int = 0; private set
    var dialogCalls: Int = 0; private set
    var shopCalls: Int = 0; private set
    var overworldCalls: Int = 0; private set
    var scanCalls: Int = 0; private set
    var verifyCalls: Int = 0; private set
    var shopMenuPhaseCalls: Int = 0; private set
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

    override suspend fun classifyShopMenuPhase(
        screenshotBase64: String?,
    ): HaikuConsult.ShopMenuPhaseClassification {
        val res = shopMenuPhases.getOrNull(shopMenuPhaseCalls)
            ?: HaikuConsult.ShopMenuPhaseClassification(HaikuConsult.ShopMenuPhase.UNKNOWN, 0.0)
        shopMenuPhaseCalls++
        return res
    }

    override suspend fun adviseShopPurchase(
        screenshotBase64: String?,
        contextText: String,
    ): HaikuConsult.ShopPurchaseAdvice {
        return HaikuConsult.ShopPurchaseAdvice("Fail", "fake-not-scripted", 0.0)
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
