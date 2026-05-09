package knes.agent.perception

import knes.agent.explorer.HaikuConsult

/**
 * Decides whether a shop dialog overlay (BUY/SELL/EXIT menu) is currently on
 * screen. Replaces the old `mapId != 0 && mapId != initialMapId` heuristic
 * which was structurally wrong: FF1 NES shops are NPC dialog overlays inside
 * the town overlay layer (mapflags.bit0=1, mapId=0), NOT sub-maps.
 *
 * Empirical evidence (2026-05-09 run #7 iter 23): party fully inside the
 * Coneria weapon shop dialog (Welcome + Buy/Sell/Exit + 400G display) while
 * `currentMapId=0` and `mapflags=1`. The mapId-only check rejected Done.
 *
 * Detection strategy: cheap RAM gate first, vision confirmation second.
 *   - RAM rules out impossible regimes (genuine overworld, castle, battle).
 *   - Vision confirms the menu is actually open (returns kind != "unknown").
 *
 * Sub-shop case (mapflags.bit0=1, mapId>0, not castle) also passes the RAM
 * gate; vision is the same definitive check.
 */
object ShopUiDetector {

    /** Castle sub-mapIds — never a shop. */
    private val CASTLE_MAP_IDS = setOf(8, 24)

    /** Shop kinds returned by [HaikuConsult.classifyShopMenu]. */
    private val SHOP_KINDS = setOf("weapon", "armor", "whiteMagic", "blackMagic", "item")

    data class Detection(
        val open: Boolean,
        /** Short tag for traces — "ram_overworld" / "ram_castle" / "ram_battle" /
         *  "vision_kind=weapon" / "vision_unknown" / "ram_pass_no_vision". */
        val source: String,
        val costUsd: Double,
        val kind: String? = null,
    )

    /**
     * Quick RAM-only gate. Returns [Detection.open]=false with the reason when
     * the regime makes a shop dialog impossible. Returns null when the regime
     * is consistent with a shop being open (caller must run vision to confirm).
     */
    fun ramGate(ram: Map<String, Int>): Detection? {
        val mapflags = ram["mapflags"] ?: 0
        val mapId = ram["currentMapId"] ?: 0
        val screenState = ram["screenState"] ?: 0
        if ((mapflags and 0x01) == 0) {
            return Detection(false, "ram_overworld", 0.0)
        }
        if (mapId in CASTLE_MAP_IDS) {
            return Detection(false, "ram_castle", 0.0)
        }
        if (screenState != 0) {
            return Detection(false, "ram_battle", 0.0)
        }
        return null
    }

    /**
     * Full detection: RAM gate, then vision. [vision] may be null in tests; in
     * that case a passing RAM gate yields source="ram_pass_no_vision" with
     * open=true (test-only fast path; production always wires a vision).
     */
    suspend fun detect(
        ram: Map<String, Int>,
        screenshotBase64: String?,
        vision: HaikuConsult?,
    ): Detection {
        val gate = ramGate(ram)
        if (gate != null) return gate
        if (vision == null) {
            return Detection(true, "ram_pass_no_vision", 0.0)
        }
        val classification = vision.classifyShopMenu(screenshotBase64)
        return if (classification.kind in SHOP_KINDS) {
            Detection(true, "vision_kind=${classification.kind}",
                classification.costUsd, classification.kind)
        } else {
            Detection(false, "vision_unknown", classification.costUsd)
        }
    }
}
