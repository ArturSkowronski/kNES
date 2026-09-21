package knes.agent.runtime

import knes.agent.campaign.Ff1Campaign

import kotlin.math.roundToInt

/** RAM-derived FF1 helpers. Gold is 24-bit LE; HP is 16-bit LE; weapon bytes are flag|id. */
object StrategyContext {
    fun minHpPct(ram: Map<String, Int>): Int {
        val pcts = (1..4).mapNotNull { i ->
            val cur = read16(ram, "char${i}_hpLow", "char${i}_hpHigh") ?: return@mapNotNull null
            val max = read16(ram, "char${i}_maxHpLow", "char${i}_maxHpHigh") ?: return@mapNotNull null
            if (max == 0) null else (100.0 * cur / max).roundToInt()
        }
        return pcts.minOrNull() ?: 100
    }

    fun totalGold(ram: Map<String, Int>): Int = Ff1Campaign.gold(ram)

    fun weaponSlot(ram: Map<String, Int>, char: Int, slot: Int): Int =
        ram["char${char}_weapon${slot}"] ?: 0

    fun weaponId(byte: Int): Int = byte and 0x7F

    fun isEquipped(byte: Int): Boolean = (byte and 0x80) != 0

    private fun read16(ram: Map<String, Int>, lowKey: String, highKey: String): Int? {
        val lo = ram[lowKey] ?: return null
        val hi = ram[highKey] ?: return null
        return (hi shl 8) or lo
    }
}
