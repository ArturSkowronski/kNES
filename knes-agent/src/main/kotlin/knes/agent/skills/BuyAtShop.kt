package knes.agent.skills

import knes.agent.explorer.HaikuConsult
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

    data class PairResult(
        val itemSlot: Int,
        val charSlot: Int,
        val ok: Boolean,
        val message: String,
    )

    /**
     * V5.43 (2026-05-09): list-mode purchase. Stays in the shop dialog for ALL
     * pairs in a single keeper engagement, eliminating the NPC-drift problem
     * where the shopkeeper wanders away between separate BuyAtShop calls.
     *
     * Per-pair flow (executed in a continuous BUY/SELL/EXIT session):
     *   1. Reset cursor to BUY: Up × 2 (idempotent — at top of 3-row menu).
     *   2. A → item list (cursor on item 0).
     *   3. Down × itemSlot, A → "for whom?" (cursor on char 1).
     *   4. Down × (charSlot - 1), A → "Buy for X? YES/NO".
     *   5. A → YES → dismiss A-taps until gold drop + inv increase OR 5 unchanged.
     *   6. B × 3 to reset to BUY/SELL/EXIT for next pair.
     *
     * Single open A-tap fires only when [menuAlreadyOpen]=false (caller knows
     * dialog state via vision / probe). Closing 5 × B at the end fully exits.
     *
     * Returns one PairResult per input pair, in input order. Pairs that error
     * out structurally (NotInShop, LandmarkKindMismatch) abort the rest with
     * a synthetic failure for each remaining pair.
     */
    suspend fun invokeMany(
        pairs: List<Pair<Int, Int>>,
        expectedKeeperKind: String,
        menuAlreadyOpen: Boolean,
    ): List<PairResult> {
        if (pairs.isEmpty()) return emptyList()
        if (expectedKeeperKind != "weapon") {
            return pairs.map { PairResult(it.first, it.second, false,
                "UnsupportedKind: BuyAtShop currently supports kind=weapon only (got $expectedKeeperKind)") }
        }
        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        val mapflagsBit0 = ((pre["mapflags"] ?: 0) and 0x01) != 0
        val inStandardMap = mapflagsBit0 || mapId > 0
        if (!inStandardMap) {
            return pairs.map { PairResult(it.first, it.second, false,
                "NotInShop: not in standard map (mapflags.bit0=0, mapId=0)") }
        }
        val candidates = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        val landmark = if (mapId != 0) {
            candidates.firstOrNull { it.mapId == mapId }
        } else {
            candidates.firstOrNull { parseKind(it.note) == expectedKeeperKind }
        } ?: return pairs.map { PairResult(it.first, it.second, false,
            "NotInShop: no NPC_SHOPKEEPER landmark for ${if (mapId != 0) "mapId=$mapId" else "kind=$expectedKeeperKind"}") }
        val landmarkKind = parseKind(landmark.note)
        if (landmarkKind != expectedKeeperKind) {
            return pairs.map { PairResult(it.first, it.second, false,
                "LandmarkKindMismatch: expected=$expectedKeeperKind landmark=$landmarkKind") }
        }
        val items = parseItems(landmark.note)

        // Open dialog if not already open. After this point we are at
        // "Welcome + BUY/SELL/EXIT" with cursor on Buy (or somewhere on the
        // 3-row menu — the per-pair Up×2 reset normalises it).
        if (!menuAlreadyOpen) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
        }

        val results = mutableListOf<PairResult>()
        for ((itemSlot, charSlot) in pairs) {
            val priceCheck = items.getOrNull(itemSlot)?.second
            if (priceCheck == null) {
                results += PairResult(itemSlot, charSlot, false,
                    "Bad args: itemSlot $itemSlot out of range (have ${items.size})")
                continue
            }
            val ramSnap = toolset.getState().ram
            val preGold = StrategyContext.totalGold(ramSnap)
            if (preGold < priceCheck) {
                results += PairResult(itemSlot, charSlot, false,
                    "InsufficientGold: preGold=$preGold expectedPrice=$priceCheck")
                continue
            }
            val preInvSum = (0..3).sumOf {
                StrategyContext.weaponId(StrategyContext.weaponSlot(ramSnap, charSlot, it))
            }

            // Reset cursor to BUY (idempotent) and select.
            repeat(2) { toolset.tap(button = "Up", count = 1, pressFrames = 3, gapFrames = 10) }
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            // Item nav.
            repeat(itemSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            // For-whom nav.
            repeat(charSlot - 1) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            // YES on confirm.
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)

            var dismissTaps = 0
            var unchangedTaps = 0
            var resolved: PairResult? = null
            while (dismissTaps < maxDismissFrames) {
                toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
                dismissTaps++
                val r = toolset.getState().ram
                val curGold = StrategyContext.totalGold(r)
                val curInvSum = (0..3).sumOf {
                    StrategyContext.weaponId(StrategyContext.weaponSlot(r, charSlot, it))
                }
                if (curGold < preGold && curInvSum > preInvSum) {
                    resolved = PairResult(itemSlot, charSlot, true,
                        "Bought: cost=${preGold - curGold} char=$charSlot slot=$itemSlot " +
                            "weaponSum $preInvSum->$curInvSum")
                    break
                }
                if (curGold == preGold && curInvSum == preInvSum) {
                    unchangedTaps++
                    if (unchangedTaps >= wrongClassFrames) {
                        resolved = PairResult(itemSlot, charSlot, false,
                            "WrongClass: char=$charSlot itemSlot=$itemSlot — gold/inventory " +
                                "unchanged after $unchangedTaps dismiss taps")
                        break
                    }
                } else {
                    unchangedTaps = 0
                }
            }
            results += (resolved ?: PairResult(itemSlot, charSlot, false,
                "DismissCapExhausted: $maxDismissFrames taps without confirmed gold drop"))

            // V5.43.2: B × 1 only (run #11 evidence: B × 3 over-exits the
            // shop entirely. From the dismiss-loop end state — item list or
            // "another?" prompt — one B reliably backs to BUY/SELL/EXIT.
            // Two B's would close the shop dialog from item list. The next
            // iter's Up × 2 + A normalises cursor to Buy regardless.)
            toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12)
        }

        // Final exit: 5 × B fully closes any remaining dialog layer back to
        // "facing keeper, no menu".
        repeat(5) { toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12) }
        return results
    }

    /**
     * V5.44 (2026-05-09): vision-classified state-machine purchase. Uses
     * [vision.classifyShopMenuPhase] to observe which sub-screen of the FF1
     * shop dialog is currently drawn after each tap, and dispatches the next
     * action accordingly. Replaces the brittle "guess by tap count" approach.
     *
     * Trade-off: ~5-8 vision calls per pair (~$0.025-0.04 per pair) vs
     * empirically reliable per-pair correctness. Acceptable when previous
     * deterministic approach was buying only 1/4 chars per run.
     *
     * Flow per pair:
     *   1. Classify phase. Drive into MAIN_MENU regardless of starting state
     *      (B-tap to back out of nested screens; A-tap to advance Welcome).
     *   2. From MAIN_MENU: Up×2 + A → ITEM_LIST.
     *   3. Down × itemSlot + A → FOR_WHOM.
     *   4. Down × (charSlot - 1) + A → BUY_CONFIRM.
     *   5. A on YES (default cursor position) → dismiss loop until gold
     *      drops + inv increases (Bought) or 5 unchanged (WrongClass).
     *   6. After result: B × 1 to back toward MAIN_MENU for next pair.
     *
     * On CLOSED phase mid-batch: aborts remaining pairs (caller must
     * re-engage keeper).
     */
    suspend fun invokeManyStateful(
        pairs: List<Pair<Int, Int>>,
        expectedKeeperKind: String,
        vision: HaikuConsult,
        traceLog: ((String) -> Unit)? = null,
    ): List<PairResult> {
        val log = { msg: String -> traceLog?.invoke(msg) ?: Unit }
        if (pairs.isEmpty()) return emptyList()
        if (expectedKeeperKind != "weapon") {
            return pairs.map { PairResult(it.first, it.second, false,
                "UnsupportedKind: BuyAtShop currently supports kind=weapon only (got $expectedKeeperKind)") }
        }
        val pre0 = toolset.getState().ram
        val mapId0 = pre0["currentMapId"] ?: 0
        val mapflagsBit0 = ((pre0["mapflags"] ?: 0) and 0x01) != 0
        if (!mapflagsBit0 && mapId0 == 0) {
            return pairs.map { PairResult(it.first, it.second, false,
                "NotInShop: not in standard map (mapflags.bit0=0, mapId=0)") }
        }
        val candidates = landmarks.findByKind(LandmarkKind.NPC_SHOPKEEPER)
        val landmark = if (mapId0 != 0) {
            candidates.firstOrNull { it.mapId == mapId0 }
        } else {
            candidates.firstOrNull { parseKind(it.note) == expectedKeeperKind }
        } ?: return pairs.map { PairResult(it.first, it.second, false,
            "NotInShop: no NPC_SHOPKEEPER landmark for ${if (mapId0 != 0) "mapId=$mapId0" else "kind=$expectedKeeperKind"}") }
        val landmarkKind = parseKind(landmark.note)
        if (landmarkKind != expectedKeeperKind) {
            return pairs.map { PairResult(it.first, it.second, false,
                "LandmarkKindMismatch: expected=$expectedKeeperKind landmark=$landmarkKind") }
        }
        val items = parseItems(landmark.note)

        suspend fun classify(): HaikuConsult.ShopMenuPhase {
            val shot = toolset.getScreen().base64
            val res = vision.classifyShopMenuPhase(shot)
            return res.phase
        }

        // Drive into MAIN_MENU. Returns true if reached, false if shop closed.
        // V5.44.1 (2026-05-09): UNKNOWN handler is now step+retry (no taps)
        // for the first 2 attempts. Run #15 evidence: blindly tapping B on
        // UNKNOWN closed the shop entirely when the actual phase was MAIN_MENU
        // misclassified by Gemini stochastic.
        suspend fun driveToMainMenu(maxSteps: Int = 10): Boolean {
            var unknownCount = 0
            for (step in 0 until maxSteps) {
                val phase = classify()
                log("invokeManyStateful.driveToMainMenu[$step]: phase=$phase unknownCount=$unknownCount")
                when (phase) {
                    HaikuConsult.ShopMenuPhase.MAIN_MENU -> return true
                    HaikuConsult.ShopMenuPhase.WELCOME -> {
                        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
                        unknownCount = 0
                    }
                    HaikuConsult.ShopMenuPhase.ITEM_LIST,
                    HaikuConsult.ShopMenuPhase.FOR_WHOM,
                    HaikuConsult.ShopMenuPhase.BUY_CONFIRM,
                    HaikuConsult.ShopMenuPhase.ANOTHER -> {
                        toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12)
                        unknownCount = 0
                    }
                    HaikuConsult.ShopMenuPhase.UNKNOWN -> {
                        unknownCount++
                        if (unknownCount >= 3) {
                            // Tap B only after 3 consecutive UNKNOWN — assume
                            // we're really in some unrecognized sub-menu.
                            toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12)
                            unknownCount = 0
                        }
                        // Otherwise: just step frames and re-classify.
                    }
                    HaikuConsult.ShopMenuPhase.CLOSED -> return false
                }
                toolset.step(buttons = emptyList(), frames = 8)
            }
            return false
        }

        val results = mutableListOf<PairResult>()
        for ((itemSlot, charSlot) in pairs) {
            val priceCheck = items.getOrNull(itemSlot)?.second
            if (priceCheck == null) {
                results += PairResult(itemSlot, charSlot, false,
                    "Bad args: itemSlot $itemSlot out of range (have ${items.size})")
                continue
            }

            // Step 1: drive into MAIN_MENU.
            if (!driveToMainMenu()) {
                results += PairResult(itemSlot, charSlot, false,
                    "ShopClosed: phase classifier returned CLOSED before pair (itemSlot=$itemSlot, charSlot=$charSlot)")
                // Remaining pairs likely also fail; mark them with synthetic result.
                continue
            }

            val ramSnap = toolset.getState().ram
            val preGold = StrategyContext.totalGold(ramSnap)
            if (preGold < priceCheck) {
                results += PairResult(itemSlot, charSlot, false,
                    "InsufficientGold: preGold=$preGold expectedPrice=$priceCheck")
                continue
            }
            val preInvSum = (0..3).sumOf {
                StrategyContext.weaponId(StrategyContext.weaponSlot(ramSnap, charSlot, it))
            }

            // Step 2: at MAIN_MENU, force cursor to BUY (Up×2 in 3-row no-wrap menu).
            repeat(2) { toolset.tap(button = "Up", count = 1, pressFrames = 3, gapFrames = 10) }
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            toolset.step(buttons = emptyList(), frames = 6)

            // Verify we landed on ITEM_LIST.
            val afterBuyPhase = classify()
            log("invokeManyStateful.afterBuySelect: phase=$afterBuyPhase")
            if (afterBuyPhase != HaikuConsult.ShopMenuPhase.ITEM_LIST) {
                results += PairResult(itemSlot, charSlot, false,
                    "PhaseMismatch: expected ITEM_LIST after BUY-A, got $afterBuyPhase")
                // Try to recover by B-spamming to MAIN_MENU and continuing next pair.
                continue
            }

            // Step 3: navigate to itemSlot and confirm.
            repeat(itemSlot) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            toolset.step(buttons = emptyList(), frames = 6)

            // Step 4: navigate to charSlot.
            repeat(charSlot - 1) { toolset.tap(button = "Down", count = 1, pressFrames = 3, gapFrames = 10) }
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)
            toolset.step(buttons = emptyList(), frames = 6)

            // Step 5: confirm YES (cursor on YES by default in BUY_CONFIRM).
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 20)

            // Step 6: dismiss loop watching for gold drop.
            var dismissTaps = 0
            var unchangedTaps = 0
            var resolved: PairResult? = null
            while (dismissTaps < maxDismissFrames) {
                toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 15)
                dismissTaps++
                val r = toolset.getState().ram
                val curGold = StrategyContext.totalGold(r)
                val curInvSum = (0..3).sumOf {
                    StrategyContext.weaponId(StrategyContext.weaponSlot(r, charSlot, it))
                }
                if (curGold < preGold && curInvSum > preInvSum) {
                    resolved = PairResult(itemSlot, charSlot, true,
                        "Bought: cost=${preGold - curGold} char=$charSlot slot=$itemSlot " +
                            "weaponSum $preInvSum->$curInvSum")
                    break
                }
                if (curGold == preGold && curInvSum == preInvSum) {
                    unchangedTaps++
                    if (unchangedTaps >= wrongClassFrames) {
                        resolved = PairResult(itemSlot, charSlot, false,
                            "WrongClass: char=$charSlot itemSlot=$itemSlot — gold/inventory " +
                                "unchanged after $unchangedTaps dismiss taps")
                        break
                    }
                } else {
                    unchangedTaps = 0
                }
            }
            results += (resolved ?: PairResult(itemSlot, charSlot, false,
                "DismissCapExhausted: $maxDismissFrames taps without confirmed gold drop"))
        }

        // Final exit: keep tapping B until phase classifier reports CLOSED or
        // we've B-spammed 8 times.
        for (i in 0 until 8) {
            val phase = classify()
            if (phase == HaikuConsult.ShopMenuPhase.CLOSED) break
            toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12)
            toolset.step(buttons = emptyList(), frames = 6)
        }
        return results
    }
}
