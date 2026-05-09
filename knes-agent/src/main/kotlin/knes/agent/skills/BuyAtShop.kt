package knes.agent.skills

import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.runtime.StrategyContext
import knes.agent.tools.EmulatorToolset

/**
 * Buy a single item from the shop the party is currently inside. Reads the
 * cached NPC_SHOPKEEPER landmark for kind validation and price lookup.
 *
 * Args:
 *   itemSlot           — 0-indexed BUY-list slot to purchase.
 *   forCharSlot        — 1..4, character to receive the item.
 *   expectedKeeperKind — must be "weapon" (the only supported kind in Spec 2;
 *                        armor/magic shops will need separate inventory-delta logic).
 *
 * Outcomes: Bought, InsufficientGold (sync pre-check), WrongClass, NotInShop,
 *           LandmarkKindMismatch, UnsupportedKind.
 *
 * The skill issues B-taps to back out of confirm dialog before returning, but
 * does NOT exit the shop interior — caller handles building exit.
 */
class BuyAtShop(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
) : Skill {
    override val id = "buy_at_shop"
    override val description =
        "Buy one item at the current shop. Args: itemSlot, forCharSlot, expectedKeeperKind."

    // FF1 confirmation flow: ~6-12 A-press dismiss frames after YES — well below 30.
    // 30 = hard cap; if no gold drop by then, the dialog is stuck or class-rejected.
    private val maxDismissFrames = 30
    // 5 consecutive unchanged dismiss frames = clear signal the purchase was rejected
    // silently (FF1 returns to BUY list without any animation/sound on class mismatch).
    private val wrongClassFrames = 5

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val itemSlot = args["itemSlot"]?.toIntOrNull()
            ?: return failResult("Bad args: itemSlot missing/invalid", emptyMap())
        val forCharSlot = args["forCharSlot"]?.toIntOrNull()
            ?: return failResult("Bad args: forCharSlot missing/invalid", emptyMap())
        val expectedKind = args["expectedKeeperKind"]
            ?: return failResult("Bad args: expectedKeeperKind missing", emptyMap())
        // V5.40: when the boot advisor's Done was accepted because the shop UI
        // overlay was already drawn (BUY/SELL/EXIT visible via vision), the
        // first "tap A on keeper to open dialog" A-tap is unnecessary — the
        // dialog is already open. Skipping it keeps the cursor-stride aligned
        // with itemSlot/charSlot indexing. Default false (legacy "facing
        // keeper, no menu" callers).
        val menuAlreadyOpen = args["menuAlreadyOpen"]?.toBooleanStrictOrNull() ?: false

        if (expectedKind != "weapon") {
            return failResult(
                "UnsupportedKind: BuyAtShop currently supports kind=weapon only (got $expectedKind)",
                emptyMap()
            )
        }

        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        val mapflagsBit0 = ((pre["mapflags"] ?: 0) and 0x01) != 0
        // FF1 NES shop architecture: shops are NPC dialog overlays. Two valid
        // in-shop regimes:
        //   (a) town overlay   — mapId=0, mapflags.bit0=1 (most Coneria shops)
        //   (b) sub-shop interior — mapId>0 (some towns; mapflags.bit0=1 in
        //       real RAM but tests omit the field, so mapId>0 implies it).
        // The legacy `mapId == 0 → NotInShop` rejection blocked (a) entirely.
        val inStandardMap = mapflagsBit0 || mapId > 0
        if (!inStandardMap) {
            return failResult("NotInShop: not in standard map (mapflags.bit0=0, mapId=0)", pre)
        }
        // Landmark lookup: in regime (b) match by mapId; in regime (a) the
        // shopkeeper landmark is keyed only by kind in note (no per-shop mapId
        // because they all share mapId=0). Fall through kind-by-note.
        val candidates = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        val landmark = if (mapId != 0) {
            candidates.firstOrNull { it.mapId == mapId }
                ?: return failResult("NotInShop: no NPC_SHOPKEEPER landmark for mapId=$mapId", pre)
        } else {
            candidates.firstOrNull { parseKind(it.note) == expectedKind }
                ?: return failResult(
                    "NotInShop: no NPC_SHOPKEEPER landmark with kind=$expectedKind in town overlay",
                    pre,
                )
        }
        val landmarkKind = parseKind(landmark.note)
        if (landmarkKind != expectedKind) {
            return failResult(
                "LandmarkKindMismatch: expected=$expectedKind landmark=$landmarkKind",
                pre
            )
        }

        val items = parseItems(landmark.note)
        val expectedPrice = items.getOrNull(itemSlot)?.second
            ?: return failResult("Bad args: itemSlot $itemSlot out of range (have ${items.size})", pre)
        val preGold = StrategyContext.totalGold(pre)
        if (preGold < expectedPrice) {
            return SkillResult(ok = false,
                message = "InsufficientGold: preGold=$preGold expectedPrice=$expectedPrice",
                ramAfter = pre)
        }
        val preInvSum = (0..3).sumOf { StrategyContext.weaponId(StrategyContext.weaponSlot(pre, forCharSlot, it)) }

        // Open BUY menu: 1 tap A on keeper, 1 tap A on BUY.
        // When the menu is already open (advisor reached BUY/SELL/EXIT and we
        // accepted Done without exiting), skip the first A and force cursor
        // back to the BUY row via Up taps before selecting.
        if (!menuAlreadyOpen) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        } else {
            // BUY/SELL/EXIT has 3 rows; Up×2 from anywhere parks cursor on BUY.
            repeat(2) { toolset.tap(button = "Up", count = 1, pressFrames = 3, gapFrames = 10) }
        }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Cursor down itemSlot times
        repeat(itemSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Select character: cursor down (forCharSlot - 1) positions
        repeat(forCharSlot - 1) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        // Confirm YES
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)

        // Watch RAM for purchase outcome
        var dismissTaps = 0
        var unchangedTaps = 0
        while (dismissTaps < maxDismissFrames) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
            dismissTaps++
            val ram = toolset.getState().ram
            val curGold = StrategyContext.totalGold(ram)
            val curInvSum = (0..3).sumOf { StrategyContext.weaponId(StrategyContext.weaponSlot(ram, forCharSlot, it)) }
            if (curGold < preGold && curInvSum > preInvSum) {
                // 5 B-taps fully exits the dialog stack (any of: confirm-YES,
                // for-whom, item list, BUY/SELL/EXIT) back to "facing keeper,
                // no menu" so the next BuyAtShop call has a predictable
                // starting state (run #5 evidence: 2-B left state ambiguous,
                // char2/3/4 hit off-by-one). B is no-op once shop is closed.
                repeat(5) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12) }
                return SkillResult(
                    ok = true,
                    message = "Bought: cost=${preGold - curGold} char=$forCharSlot slot=$itemSlot " +
                        "weaponSum ${preInvSum}->$curInvSum",
                    ramAfter = ram,
                )
            }
            if (curGold == preGold && curInvSum == preInvSum) {
                unchangedTaps++
                if (unchangedTaps >= wrongClassFrames) {
                    // 5 B-taps fully exits the dialog stack — same rationale
                    // as the success branch: predictable post-call state.
                    repeat(5) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12) }
                    return SkillResult(ok = false,
                        message = "WrongClass: char=$forCharSlot itemSlot=$itemSlot — gold/inventory " +
                            "unchanged after $unchangedTaps dismiss taps",
                        ramAfter = ram)
                }
            } else {
                unchangedTaps = 0
            }
        }
        val final = toolset.getState().ram
        return SkillResult(ok = false,
            message = "DismissCapExhausted: $maxDismissFrames taps without confirmed gold drop " +
                "(likely WrongClass or stuck dialog)",
            ramAfter = final)
    }

    private fun parseKind(note: String): String {
        val m = Regex("kind=([a-zA-Z]+)").find(note) ?: return "unknown"
        return m.groupValues[1]
    }
    private fun parseItems(note: String): List<Pair<String, Int>> {
        val itemsPart = Regex("items=([^;]*)").find(note)?.groupValues?.get(1) ?: return emptyList()
        return itemsPart.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) null else (parts[0] to (parts[1].toIntOrNull() ?: return@mapNotNull null))
        }
    }
    private fun failResult(message: String, ram: Map<String, Int>) =
        SkillResult(ok = false, message = message, ramAfter = ram)
}
