package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StrategyContextTest : FunSpec({
    fun ramOf(vararg pairs: Pair<String, Int>) = pairs.toMap()

    test("minLevel maps stored level-1 byte to actual level (min across 4 chars)") {
        val ram = ramOf(
            "char1_level" to 0, "char2_level" to 2,
            "char3_level" to 4, "char4_level" to 1,
        )
        StrategyContext.minLevel(ram) shouldBe 1
    }
    test("minLevel returns 0 when char_level fields missing") {
        StrategyContext.minLevel(emptyMap()) shouldBe 0
    }
    test("minHpPct: smallest char's currentHP / maxHP × 100, rounded") {
        val ram = ramOf(
            "char1_hpLow" to 15, "char1_hpHigh" to 0, "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "char2_hpLow" to 10, "char2_hpHigh" to 0, "char2_maxHpLow" to 40, "char2_maxHpHigh" to 0,
            "char3_hpLow" to 20, "char3_hpHigh" to 0, "char3_maxHpLow" to 20, "char3_maxHpHigh" to 0,
            "char4_hpLow" to 20, "char4_hpHigh" to 0, "char4_maxHpLow" to 20, "char4_maxHpHigh" to 0,
        )
        StrategyContext.minHpPct(ram) shouldBe 25
    }
    test("minHpPct returns 100 when maxHp fields missing (assume healthy)") {
        StrategyContext.minHpPct(emptyMap()) shouldBe 100
    }
    test("totalGold combines low/mid/high into 24-bit LE") {
        val ram = ramOf("goldLow" to 0x56, "goldMid" to 0x34, "goldHigh" to 0x12)
        StrategyContext.totalGold(ram) shouldBe 0x123456
    }
    test("totalGold zero when fields missing") {
        StrategyContext.totalGold(emptyMap()) shouldBe 0
    }
    test("manhattanDistance computes |dx|+|dy|") {
        StrategyContext.manhattanDistance(157, 158, 157, 141) shouldBe 17
        StrategyContext.manhattanDistance(0, 0, 0, 0) shouldBe 0
    }
    test("summarize produces deterministic single-line text for prompt") {
        val ram = ramOf(
            "char1_level" to 1, "char2_level" to 1, "char3_level" to 1, "char4_level" to 1,
            "char1_hpLow" to 20, "char1_hpHigh" to 0, "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "char2_hpLow" to 20, "char2_hpHigh" to 0, "char2_maxHpLow" to 20, "char2_maxHpHigh" to 0,
            "char3_hpLow" to 20, "char3_hpHigh" to 0, "char3_maxHpLow" to 20, "char3_maxHpHigh" to 0,
            "char4_hpLow" to 20, "char4_hpHigh" to 0, "char4_maxHpLow" to 20, "char4_maxHpHigh" to 0,
            "goldLow" to 0x90, "goldMid" to 0x01, "goldHigh" to 0,
            "worldX" to 157, "worldY" to 158, "currentMapId" to 0
        )
        val out = StrategyContext.summarize(ram, innTile = 152 to 159, bridgeTile = 157 to 141)
        out shouldBe "min_level=2 min_hp%=100 gold=400 pos=(157,158) inn_dist=6 bridge_dist=17"
    }
})
