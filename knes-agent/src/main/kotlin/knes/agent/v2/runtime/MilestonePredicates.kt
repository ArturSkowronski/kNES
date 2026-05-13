package knes.agent.v2.runtime

/**
 * Deterministic milestone predicates — single source of truth used by both
 * `advanceMilestones` (to LATCH "done" when first satisfied) and the Reviewer
 * (to RE-VERIFY "done" milestones still hold against current RAM, catching
 * regressions like a savestate restore that drops weapons).
 */
object MilestonePredicates {
    /** True iff character `c` holds any non-zero weapon in any of slots 0..3. */
    fun charHoldsAny(c: Int, ram: Map<String, Int>): Boolean =
        (0..3).any { s -> (ram["char${c}_weapon${s}"] ?: 0) != 0 }

    /** True iff character `c` has any weapon with bit7 set (equipped). */
    fun charHasEquipped(c: Int, ram: Map<String, Int>): Boolean =
        (0..3).any { s -> ((ram["char${c}_weapon${s}"] ?: 0) and 0x80) != 0 }

    fun evaluate(
        id: String,
        phase: Phase,
        ram: Map<String, Int>,
        prereqDone: Map<String, Boolean> = emptyMap(),
    ): Boolean = when (id) {
        "boot"          -> phase != Phase.Boot
        // Require party meaningfully INSIDE Coneria, not on entry-row tile.
        "enter_coneria" -> phase == Phase.Town && ((ram["smPlayerY"] ?: 30) <= 25)
        // arm_party — at least 2 of 4 chars have a weapon equipped (bit7).
        // Tightened from "any" (latched after 1) but relaxed from "all 4"
        // because BuyAtShop sometimes skips chars due to NPC drift / shop UI
        // quirks; requiring all 4 left runs stuck at 2/4 indefinitely.
        "arm_party"     -> (1..4).count { c -> charHasEquipped(c, ram) } >= 2
        "exit_coneria"  -> (prereqDone["enter_coneria"] == true) && phase == Phase.Overworld
        "grind"         -> (1..4).any { c -> (ram["char${c}_xpLow"] ?: 0) > 0 || (ram["char${c}_xpHigh"] ?: 0) > 0 }
        else            -> false
    }

    /**
     * Per-character weapon status digest for prompts. Returns a compact line
     * like "char1:Fighter held=[3] equipped=[3] | char2:Thief held=[] ..."
     * so the Advisor + Executor know exactly which slots are still empty.
     */
    fun partyWeaponDigest(ram: Map<String, Int>): String {
        val classes = mapOf(0 to "Fighter", 1 to "Thief", 2 to "BlackBelt",
                            3 to "RedMage", 4 to "WhiteMage", 5 to "BlackMage")
        return (1..4).joinToString(" | ") { c ->
            val cls = classes[ram["char${c}_class"] ?: -1] ?: "?"
            val held = (0..3).mapNotNull { s ->
                val v = ram["char${c}_weapon${s}"] ?: 0
                if (v == 0) null else (v and 0x7F).toString() + (if (v >= 0x80) "*" else "")
            }
            "char$c:$cls held=[${held.joinToString(",")}]"
        }
    }
}
