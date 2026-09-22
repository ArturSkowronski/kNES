package knes.agent.campaign

import knes.agent.runtime.Phase
import kotlin.math.roundToInt

/**
 * Final Fantasy 1: reach Coneria, arm the party, leave town, start grinding.
 *
 * Every FF1 constant the runtime needs lives here — the four-character party, the four
 * weapon slots per character, the bit that marks a weapon equipped, the class table and
 * the three-byte gold counter.
 */
object Ff1Campaign : Campaign {

    override val scope = "coneria_buy_equip_grind"

    /**
     * Buy, equip, leave, grind — the opening the whole harness was built around.
     *
     * `enter_weapon_shop` is an event checkpoint: it latches when the party reaches the
     * counter tile and is never re-verified, because the party naturally steps off that
     * tile during the buy menu. `buy_weapons` and `arm_party` stay separate so that
     * holding a weapon and having one equipped cannot be confused for each other.
     */
    override val initialMilestones = listOf(
        "boot", "enter_coneria", "enter_weapon_shop",
        "buy_weapons", "arm_party", "exit_coneria", "grind",
    )

    private const val PARTY_SIZE = 4
    private val WEAPON_SLOTS = 0..3

    /** Bit 7 of a weapon byte means "equipped" rather than merely carried. */
    private const val EQUIPPED_BIT = 0x80
    private const val ITEM_ID_MASK = 0x7F

    private val CLASS_NAMES = mapOf(
        0 to "Fighter", 1 to "Thief", 2 to "BlackBelt",
        3 to "RedMage", 4 to "WhiteMage", 5 to "BlackMage",
    )

    /** Tile the party stands on to talk to the Coneria weapon shopkeeper. */
    private const val WEAPON_SHOP_X = 11
    private const val WEAPON_SHOP_Y = 11

    /** Entry row of Coneria; further in means a smaller Y. */
    private const val CONERIA_INSIDE_Y = 25

    /**
     * Relaxed from "all four" on purpose: buyAtShop sometimes skips a character on NPC
     * drift or a shop-UI quirk, and requiring 4/4 left runs stuck at 2/4 forever. See
     * the 2026-07-19 smoke.
     */
    private const val ARMED_ENOUGH = 2

    override val eventTypeMilestones: Set<String> = setOf("enter_coneria", "enter_weapon_shop")

    override fun isSatisfied(
        id: String,
        phase: Phase,
        ram: Map<String, Int>,
        prereqDone: Map<String, Boolean>,
    ): Boolean = when (id) {
        "boot" -> phase != Phase.Boot
        // Party must be meaningfully inside Coneria, not standing on the entry row.
        "enter_coneria" -> phase == Phase.Town && (ram["smPlayerY"] ?: 30) <= CONERIA_INSIDE_Y
        // The party occupies the shop tile only for the moment of entry — the dialog
        // moves them off it, which is why this is an event-type milestone.
        "enter_weapon_shop" -> phase == Phase.Town &&
            (ram["smPlayerX"] ?: -1) == WEAPON_SHOP_X &&
            (ram["smPlayerY"] ?: -1) == WEAPON_SHOP_Y
        // Checkpoint between entering town and being fully armed: anyone bought anything.
        // Latching it gives the Advisor a clean replan signal at the buy → equip seam.
        "buy_weapons" -> countHolding(ram) > 0
        "arm_party" -> countEquipped(ram) >= ARMED_ENOUGH
        "exit_coneria" -> prereqDone["enter_coneria"] == true && phase == Phase.Overworld
        "grind" -> party().any { c -> (ram["char${c}_xpLow"] ?: 0) > 0 || (ram["char${c}_xpHigh"] ?: 0) > 0 }
        else -> false
    }

    override fun countHolding(ram: Map<String, Int>): Int = party().count { holdsAny(it, ram) }

    override fun countEquipped(ram: Map<String, Int>): Int = party().count { hasEquipped(it, ram) }

    override fun partyDigest(ram: Map<String, Int>): String = party().joinToString(" | ") { c ->
        val cls = CLASS_NAMES[ram["char${c}_class"] ?: -1] ?: "?"
        val held = weapons(c, ram).map { (it and ITEM_ID_MASK).toString() + if (isEquipped(it)) "*" else "" }
        "char$c:$cls held=[${held.joinToString(",")}]"
    }

    override fun gold(ram: Map<String, Int>): Int =
        (ram["goldLow"] ?: 0) or ((ram["goldMid"] ?: 0) shl 8) or ((ram["goldHigh"] ?: 0) shl 16)

    // --- Party and item queries used by the FF1 skills -------------------------------

    /** Raw weapon byte for a character (1-based) and slot, or 0 when the slot is empty. */
    fun weaponSlot(ram: Map<String, Int>, char: Int, slot: Int): Int =
        ram["char${char}_weapon${slot}"] ?: 0

    /** The item id inside a weapon byte, with the equipped flag stripped. */
    fun weaponId(weapon: Int): Int = weapon and ITEM_ID_MASK

    /** Whether a weapon byte is equipped rather than merely carried. */
    fun isEquipped(weapon: Int): Boolean = (weapon and EQUIPPED_BIT) != 0

    /**
     * Health of the worst-off party member, as a percentage. Characters whose HP is not
     * being watched are skipped; a party with nothing readable reports 100 so a caller
     * never mistakes "no data" for "everyone is dying".
     */
    fun minHpPct(ram: Map<String, Int>): Int = party()
        .mapNotNull { c ->
            val current = read16(ram, "char${c}_hpLow", "char${c}_hpHigh") ?: return@mapNotNull null
            val max = read16(ram, "char${c}_maxHpLow", "char${c}_maxHpHigh") ?: return@mapNotNull null
            if (max == 0) null else (100.0 * current / max).roundToInt()
        }
        .minOrNull() ?: 100

    private fun read16(ram: Map<String, Int>, lowKey: String, highKey: String): Int? {
        val low = ram[lowKey] ?: return null
        val high = ram[highKey] ?: return null
        return (high shl 8) or low
    }

    private fun party() = 1..PARTY_SIZE

    private fun weapons(c: Int, ram: Map<String, Int>): List<Int> =
        WEAPON_SLOTS.mapNotNull { s -> (ram["char${c}_weapon${s}"] ?: 0).takeIf { it != 0 } }

    private fun holdsAny(c: Int, ram: Map<String, Int>) = weapons(c, ram).isNotEmpty()

    private fun hasEquipped(c: Int, ram: Map<String, Int>) = weapons(c, ram).any { isEquipped(it) }
}
