package knes.agent.v2.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PhaseTest : StringSpec({
    "Town overlay: mapId=0, mapflags.bit0=1 → Town (not Indoors)" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 1,
            "char1_hpLow" to 30, "worldX" to 147,
        )) shouldBe Phase.Town
    }

    "Overworld: mapId=0, mapflags=0 → Overworld" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 0,
            "char1_hpLow" to 30, "worldX" to 147,
        )) shouldBe Phase.Overworld
    }

    "Interior building: mapId>0 → Indoors" {
        Phase.fromRam(mapOf(
            "currentMapId" to 8, "mapflags" to 1,
            "char1_hpLow" to 30, "worldX" to 147,
        )) shouldBe Phase.Indoors
    }

    "Boot: no party state → Boot" {
        Phase.fromRam(mapOf("currentMapId" to 0, "mapflags" to 0)) shouldBe Phase.Boot
    }

    "Battle: screenState=0x68 → Battle (even with mapflags=0)" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 0,
            "char1_hpLow" to 35, "worldX" to 149,
            "screenState" to 0x68, "battleTurn" to 2, "enemyCount" to 5,
        )) shouldBe Phase.Battle
    }

    "Battle: battleTurn>0 alone classifies as Battle" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 0,
            "char1_hpLow" to 35, "worldX" to 149,
            "battleTurn" to 1,
        )) shouldBe Phase.Battle
    }
})
