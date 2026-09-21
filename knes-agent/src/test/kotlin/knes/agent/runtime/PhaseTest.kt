package knes.agent.runtime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The rules themselves live in `profiles/ff1.json` and are unit-tested in knes-debug.
 * These cases pin the FF1 behaviours the agent depends on, so a profile edit that breaks
 * one of them fails here too.
 */
class PhaseTest : StringSpec({
    "Town overlay: mapId=0, mapflags.bit0=1 → Town (not Indoors)" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 1,
            "char1_hpLow" to 30, "worldX" to 147,
        ), "ff1") shouldBe Phase.Town
    }

    "Overworld: mapId=0, mapflags=0 → Overworld" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 0,
            "char1_hpLow" to 30, "worldX" to 147,
        ), "ff1") shouldBe Phase.Overworld
    }

    "Interior building: mapId>0 → Indoors" {
        Phase.fromRam(mapOf(
            "currentMapId" to 8, "mapflags" to 1,
            "char1_hpLow" to 30, "worldX" to 147,
        ), "ff1") shouldBe Phase.Indoors
    }

    "Boot: no party state → Boot" {
        Phase.fromRam(mapOf("currentMapId" to 0, "mapflags" to 0), "ff1") shouldBe Phase.Boot
    }

    "Battle: screenState=0x68 → Battle (even with mapflags=0)" {
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 0,
            "char1_hpLow" to 35, "worldX" to 149,
            "screenState" to 0x68, "battleTurn" to 2, "enemyCount" to 5,
        ), "ff1") shouldBe Phase.Battle
    }

    "Post-battle: stale battleTurn/enemyCount without screenState=0x68 → not Battle" {
        // After victory, screenState transitions away from 0x68 but
        // battleTurn/enemyCount retain their last values. Phase must
        // recover to Overworld (or whatever map+mapflags says) so the
        // agent can continue the campaign loop — see Smoke 1 v7 sticky
        // bug in 2026-05-12-v2-smoke handoff.
        Phase.fromRam(mapOf(
            "currentMapId" to 0, "mapflags" to 0,
            "char1_hpLow" to 35, "worldX" to 149,
            "screenState" to 0x60, "battleTurn" to 2, "enemyCount" to 5,
        ), "ff1") shouldBe Phase.Overworld
    }

    "a profile with no semantics classifies nothing" {
        Phase.fromRam(mapOf("currentMapId" to 0, "mapflags" to 1), "no-such-game") shouldBe Phase.Unknown
    }
})
