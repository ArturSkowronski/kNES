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

    test("indoors — mapflags bit 0 set with smPlayerX/Y → Indoors(party tile, isTown=false)") {
        // V5.6: canonical 'in standard map' = mapflags ($2D) bit 0 set.
        // Indoors.localX/Y carries party tile from $0068/$0069 (sm_player), not $0029/$002A scroll.
        val ram = mapOf(
            "screenState" to 0,
            "locationType" to 0xD1,
            "mapflags" to 0x01,
            "localX" to 0x05,            // sm_scroll_x — different from party
            "localY" to 0x0A,            // sm_scroll_y
            "smPlayerX" to 0x07,         // canonical party tile
            "smPlayerY" to 0x0C,
            "worldX" to 0x92,
            "worldY" to 0x9E,
            "char1_hpLow" to 0x23,
            "char1_status" to 0,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Indoors(mapId = -1, localX = 0x07, localY = 0x0C, isTown = false)
    }

    test("indoors precedence — mapflags=1 overrides world coords, picks party from sm_player") {
        // V2.1-V2.2 issue: world coords stay non-zero on overworld→interior transition.
        // V5.6: mapflags bit 0 is the trustworthy discriminator; party from sm_player_x/y.
        val ram = mapOf(
            "screenState" to 0,
            "locationType" to 0xD1,
            "mapflags" to 0x01,
            "worldX" to 146,
            "worldY" to 152,
            "localX" to 4,            // scroll
            "localY" to 9,            // scroll
            "smPlayerX" to 11,        // canonical party
            "smPlayerY" to 16,
            "char1_hpLow" to 35,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Indoors(mapId = -1, localX = 11, localY = 16, isTown = false)
    }

    test("town — mapflags=1, locType=0, $48=mapId → Indoors(isTown=true)") {
        // V5.4 + V5.6: FF1 towns (Coneria etc.) signal mapflags bit 0 = 1 just like castles,
        // but locationType stays 0 (locType=0xD1 is castle/dungeon room flag only).
        val ram = mapOf(
            "screenState" to 0,
            "locationType" to 0,
            "mapflags" to 0x01,
            "worldX" to 146,
            "worldY" to 152,
            "localX" to 4,            // sm_scroll_x
            "localY" to 25,           // sm_scroll_y
            "smPlayerX" to 11,        // sm_player_x (real party tile)
            "smPlayerY" to 32,
            "currentMapId" to 8,
            "char1_hpLow" to 35,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Indoors(mapId = 8, localX = 11, localY = 32, isTown = true)
    }

    test("legacy fallback — profile without mapflags falls back to locType + scroll heuristic") {
        // Old profiles (and this codebase's pre-V5.6 tests) didn't expose mapflags or
        // smPlayerX/Y. classify() must still classify as Indoors when locType=0xD1 or
        // $29/$2A non-zero, using $29/$2A as party (incorrect but matches V2 behaviour).
        val ram = mapOf(
            "screenState" to 0,
            "locationType" to 0xD1,
            // no mapflags, no smPlayerX/Y
            "localX" to 4,
            "localY" to 9,
            "worldX" to 146,
            "worldY" to 152,
            "char1_hpLow" to 35,
        )

        RamObserver.classify(ram) shouldBe FfPhase.Indoors(mapId = -1, localX = 4, localY = 9, isTown = false)
    }
})
