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
            "currentMapId" to 99,  // not in KNOWN_MAP_IDS, not zero either
            "char1_hpLow" to 30, "char2_hpLow" to 0, "char3_hpLow" to 0, "char4_hpLow" to 0,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 3, idleTurns = 0,
            stepsTaken = 0, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.UnknownMapTrap
    }

    test("mapId=0 + mapflags=1 (engine void) bails immediately without waiting for idle") {
        // Bogus warps like (147,154) transition to mapId=0+mapflags=1 — no real
        // interior, just engine void state. Don't wait 3 idle turns; bail now
        // and let the next run reload savestate.
        val ram = mapOf(
            "mapflags" to 0x01,
            "currentMapId" to 0,
            "char1_hpLow" to 30, "char2_hpLow" to 30, "char3_hpLow" to 30, "char4_hpLow" to 30,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 0,
            stepsTaken = 0, coverageWindow = 10, coverageDeltaInWindow = 1) shouldBe StopReason.UnknownMapTrap
    }

    test("mapId=0 + mapflags=0 (overworld coords reset) is NOT trap — we're on overworld") {
        // After party wipe / game reset the RAM may show mapflags=0 and mapId=0
        // simultaneously — that's the overworld at spawn coords being reloaded,
        // not a trap. Don't fire UnknownMapTrap.
        val ram = mapOf(
            "mapflags" to 0x00, "currentMapId" to 0,
            "char1_hpLow" to 30, "char2_hpLow" to 30, "char3_hpLow" to 30, "char4_hpLow" to 30,
        )
        SingleRun.checkRestart(ram, knownMapIds = setOf(1, 8),
            consecutiveIdleInTrap = 0, idleTurns = 2,
            stepsTaken = 5, coverageWindow = 10, coverageDeltaInWindow = 2) shouldBe null
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
