package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RamObserverTest : FunSpec({

    test("battle phase: screenState=0x68, enemy alive, party alive → Battle(enemyId, enemyHp, enemyDead=false)") {
        val ram = mapOf(
            "screenState" to 0x68,
            "enemyMainType" to 5,
            "enemy1_hpHigh" to 0x01,
            "enemy1_hpLow" to 0x20,
            "enemy1_dead" to 0,
            "char1_status" to 0,
            "char2_status" to 0,
            "char3_status" to 0,
            "char4_status" to 0,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Battle(
            enemyId = 5,
            enemyHp = (0x01 shl 8) or 0x20,
            enemyDead = false,
        )
    }

    test("party defeated: all char status low bit set overrides screenState=0x68 → PartyDefeated") {
        val ram = mapOf(
            "screenState" to 0x68,
            "enemyMainType" to 3,
            "enemy1_hpHigh" to 0x00,
            "enemy1_hpLow" to 0x50,
            "enemy1_dead" to 0,
            "char1_status" to 0x01,
            "char2_status" to 0x01,
            "char3_status" to 0x01,
            "char4_status" to 0x01,
        )

        RamObserver.classify(ram) shouldBe FfPhase.PartyDefeated
    }

    test("post-battle: screenState=0x63 → PostBattle") {
        val ram = mapOf(
            "screenState" to 0x63,
            "char1_status" to 0,
        )

        RamObserver.classify(ram) shouldBe FfPhase.PostBattle
    }

    test("overworld: screenState neither battle nor post-battle, worldX/Y present → Overworld(x, y)") {
        val ram = mapOf(
            "screenState" to 0x00,
            "worldX" to 12,
            "worldY" to 34,
            "char1_status" to 0,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Overworld(x = 12, y = 34)
    }
})
