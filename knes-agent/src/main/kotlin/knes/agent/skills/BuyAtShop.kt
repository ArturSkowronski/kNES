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
 *   expectedKeeperKind — e.g. "weapon"; skill bails on landmark mismatch.
 *
 * Outcomes: Bought, InsufficientGold (sync pre-check), WrongClass, NotInShop,
 *           LandmarkKindMismatch.
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

    private val maxDismissFrames = 30
    private val wrongClassFrames = 5

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val itemSlot = args["itemSlot"]?.toIntOrNull()
            ?: return failResult("Bad args: itemSlot missing/invalid", emptyMap())
        val forCharSlot = args["forCharSlot"]?.toIntOrNull()
            ?: return failResult("Bad args: forCharSlot missing/invalid", emptyMap())
        val expectedKind = args["expectedKeeperKind"]
            ?: return failResult("Bad args: expectedKeeperKind missing", emptyMap())

        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        if (mapId == 0) {
            return failResult("NotInShop: currentMapId=0", pre)
        }
        val landmark = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
            .firstOrNull { it.mapId == mapId }
            ?: return failResult("NotInShop: no NPC_SHOPKEEPER landmark for mapId=$mapId", pre)
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

        // Open BUY menu: 1 tap A on keeper, 1 tap A on BUY
        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
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
                repeat(2) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 15) }
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
                    repeat(3) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 15) }
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
