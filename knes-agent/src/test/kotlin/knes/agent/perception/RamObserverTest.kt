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
            "char1_hpLow" to 0x23,
            "worldX" to 0x92,
            "worldY" to 0x9e,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Battle(
            enemyId = 5,
            enemyHp = (0x01 shl 8) or 0x20,
            enemyDead = false,
        )
    }

    test("party defeated: all char status low bit set → PartyDefeated") {
        val ram = mapOf(
            "screenState" to 0x00,
            "char1_status" to 0x01,
            "char2_status" to 0x01,
            "char3_status" to 0x01,
            "char4_status" to 0x01,
            "char1_hpLow" to 0x23,
            "worldX" to 0x92,
            "worldY" to 0x9e,
        )

        RamObserver.classify(ram) shouldBe FfPhase.PartyDefeated
    }

    test("post-battle: screenState=0x63 → PostBattle") {
        val ram = mapOf(
            "screenState" to 0x63,
            "char1_status" to 0,
            "char1_hpLow" to 0x23,
            "worldX" to 0x92,
        )

        RamObserver.classify(ram) shouldBe FfPhase.PostBattle
    }

    test("overworld: screenState neither battle nor post-battle, worldX/Y and party present → Overworld(x, y)") {
        val ram = mapOf(
            "screenState" to 0x00,
            "worldX" to 0x21,
            "worldY" to 0x14,
            "char1_status" to 0,
            "char1_hpLow" to 0x23,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Overworld(x = 0x21, y = 0x14)
    }

    test("title screen — no party, no world coords, no battle screen → TitleOrMenu") {
        val ram = mapOf(
            "screenState" to 0,
            "worldX" to 0,
            "worldY" to 0,
            "char1_hpLow" to 0,
        )

        RamObserver.classify(ram) shouldBe FfPhase.TitleOrMenu
    }

    test("indoors — locationType=0xD1 with party present → Indoors(localX, localY)") {
        val ram = mapOf(
            "screenState" to 0,
            "locationType" to 0xD1,
            "localX" to 0x07,
            "localY" to 0x0C,
            "worldX" to 0x92,
            "worldY" to 0x9E,
            "char1_hpLow" to 0x23,
            "char1_status" to 0,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Indoors(mapId = -1, localX = 0x07, localY = 0x0C)
    }

    test("indoors takes precedence over overworld classification when locationType=0xD1") {
        // Even though worldX/Y are non-zero, locationType=0xD1 means we're inside a building
        // and worldX/Y are stale. This is the V2.1 root cause we fixed in V2.2.
        val ram = mapOf(
            "screenState" to 0,
            "locationType" to 0xD1,
            "worldX" to 146,
            "worldY" to 152,
            "localX" to 4,
            "localY" to 9,
            "char1_hpLow" to 35,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Indoors(mapId = -1, localX = 4, localY = 9)
    }
})
