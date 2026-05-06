package knes.agent.advisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.runtime.RecentDecisionsBuffer
import knes.agent.runtime.StrategicDecision

class StrategyAdviceTest : FunSpec({

    test("buildPrompt embeds RAM summary, recent decisions, and asks for one token") {
        val ram = mapOf(
            "char1_level" to 1, "char2_level" to 1, "char3_level" to 1, "char4_level" to 1,
            "char1_hpLow" to 10, "char1_hpHigh" to 0,
            "char1_maxHpLow" to 20, "char1_maxHpHigh" to 0,
            "char2_hpLow" to 20, "char2_hpHigh" to 0,
            "char2_maxHpLow" to 20, "char2_maxHpHigh" to 0,
            "char3_hpLow" to 20, "char3_hpHigh" to 0,
            "char3_maxHpLow" to 20, "char3_maxHpHigh" to 0,
            "char4_hpLow" to 20, "char4_hpHigh" to 0,
            "char4_maxHpLow" to 20, "char4_maxHpHigh" to 0,
            "goldLow" to 0x32, "goldMid" to 0, "goldHigh" to 0,  // 50 gp
            "worldX" to 157, "worldY" to 158
        )
        val recent = RecentDecisionsBuffer().apply {
            record(StrategicDecision.GRIND); record(StrategicDecision.GRIND); record(StrategicDecision.REST)
        }
        val prompt = StrategyAdvice.buildPrompt(
            ram = ram, recent = recent, innTile = 152 to 159, bridgeTile = 157 to 141,
            targetMinLevel = 3,
        )
        prompt shouldBe """
            min_level=2 min_hp%=50 gold=50 pos=(157,158) inn_dist=6 bridge_dist=17
            recent: [GRIND, GRIND, REST]
            target: min_level >= 3 before BRIDGE
            Reply with EXACTLY ONE token: GRIND or REST or BRIDGE.
        """.trimIndent()
    }

    test("applySanityGuards: BRIDGE with min_level<2 is overridden to GRIND") {
        val ram = mapOf("char1_level" to 0, "char2_level" to 0, "char3_level" to 0, "char4_level" to 0)
        StrategyAdvice.applySanityGuards(StrategicDecision.BRIDGE, ram, isThrashing = false) shouldBe
            StrategicDecision.GRIND
    }
    test("applySanityGuards: thrashing forces GRIND regardless of advisor output") {
        val ram = mapOf("char1_level" to 5, "char2_level" to 5, "char3_level" to 5, "char4_level" to 5)
        StrategyAdvice.applySanityGuards(StrategicDecision.REST, ram, isThrashing = true) shouldBe
            StrategicDecision.GRIND
    }
    test("applySanityGuards: passes through clean decisions") {
        val ram = mapOf("char1_level" to 2, "char2_level" to 2, "char3_level" to 2, "char4_level" to 2)
        StrategyAdvice.applySanityGuards(StrategicDecision.BRIDGE, ram, isThrashing = false) shouldBe
            StrategicDecision.BRIDGE
        StrategyAdvice.applySanityGuards(StrategicDecision.GRIND, ram, isThrashing = false) shouldBe
            StrategicDecision.GRIND
    }
})
