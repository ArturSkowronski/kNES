package knes.agent.runtime

import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * RAM-derived strategy helpers. FF1 stores char*_level as level-1 (add 1).
 * Gold is 24-bit LE across goldLow/goldMid/goldHigh. HP is 16-bit LE; max HP via maxHpLow/maxHpHigh.
 */
object StrategyContext {
    fun minLevel(ram: Map<String, Int>): Int {
        val levels = (1..4).mapNotNull { i -> ram["char${i}_level"]?.let { it + 1 } }
        return levels.minOrNull() ?: 0
    }

    fun minHpPct(ram: Map<String, Int>): Int {
        val pcts = (1..4).mapNotNull { i ->
            val cur = read16(ram, "char${i}_hpLow", "char${i}_hpHigh") ?: return@mapNotNull null
            val max = read16(ram, "char${i}_maxHpLow", "char${i}_maxHpHigh") ?: return@mapNotNull null
            if (max == 0) null else (100.0 * cur / max).roundToInt()
        }
        return pcts.minOrNull() ?: 100
    }

    fun totalGold(ram: Map<String, Int>): Int {
        val lo = ram["goldLow"] ?: 0
        val mid = ram["goldMid"] ?: 0
        val hi = ram["goldHigh"] ?: 0
        return (hi shl 16) or (mid shl 8) or lo
    }

    fun manhattanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int =
        (x1 - x2).absoluteValue + (y1 - y2).absoluteValue

    fun weaponSlot(ram: Map<String, Int>, char: Int, slot: Int): Int =
        ram["char${char}_weapon${slot}"] ?: 0

    fun weaponId(byte: Int): Int = byte and 0x7F

    fun isEquipped(byte: Int): Boolean = (byte and 0x80) != 0

    fun anyWeaponEquipped(ram: Map<String, Int>, char: Int): Boolean =
        (0..3).any { isEquipped(weaponSlot(ram, char, it)) }

    fun summarize(
        ram: Map<String, Int>,
        innTile: Pair<Int, Int>,
        bridgeTile: Pair<Int, Int>
    ): String {
        val wx = ram["worldX"] ?: 0
        val wy = ram["worldY"] ?: 0
        return "min_level=${minLevel(ram)} " +
            "min_hp%=${minHpPct(ram)} " +
            "gold=${totalGold(ram)} " +
            "pos=($wx,$wy) " +
            "inn_dist=${manhattanDistance(wx, wy, innTile.first, innTile.second)} " +
            "bridge_dist=${manhattanDistance(wx, wy, bridgeTile.first, bridgeTile.second)}"
    }

    private fun read16(ram: Map<String, Int>, lowKey: String, highKey: String): Int? {
        val lo = ram[lowKey] ?: return null
        val hi = ram[highKey] ?: return null
        return (hi shl 8) or lo
    }
}
