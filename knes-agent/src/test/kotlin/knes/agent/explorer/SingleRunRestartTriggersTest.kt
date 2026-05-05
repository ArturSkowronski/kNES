package knes.agent.explorer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SingleRunRestartTriggersTest : FunSpec({
    test("party wipe + interior triggers PartyWiped") {
        val ram = mapOf(
            "mapflags" to 0x01,
            "currentMapId" to 1,
            "char1_hpLow" to 0, "char2_hpLow" to 0, "char3_hpLow" to 0, "char4_hpLow" to 0,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 0,
            stepsTaken = 0, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.PartyWiped
    }

    test("unknown mapId trap after 3 idle turns triggers UnknownMapTrap") {
        val ram = mapOf(
            "mapflags" to 0x01,
            "currentMapId" to 0,  // not in KNOWN_MAP_IDS
            "char1_hpLow" to 30, "char2_hpLow" to 0, "char3_hpLow" to 0, "char4_hpLow" to 0,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 3, idleTurns = 0,
            stepsTaken = 0, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.UnknownMapTrap
    }

    test("idle 10 turns triggers Idle") {
        val ram = mapOf("mapflags" to 0x00, "currentMapId" to 0, "char1_hpLow" to 30)
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 10,
            stepsTaken = 20, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.Idle
    }

    test("zero coverage delta over window triggers LocalPlateau") {
        val ram = mapOf("mapflags" to 0x00, "currentMapId" to 0, "char1_hpLow" to 30)
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 0,
            stepsTaken = 11, coverageWindow = 10, coverageDeltaInWindow = 0) shouldBe StopReason.LocalPlateau
    }

    test("normal step returns null") {
        val ram = mapOf("mapflags" to 0x00, "currentMapId" to 0, "char1_hpLow" to 30)
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 2,
            stepsTaken = 5, coverageWindow = 10, coverageDeltaInWindow = 2) shouldBe null
    }
})
