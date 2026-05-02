package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class WalkOverworldToTest : FunSpec({
    test("moves at least one tile in the requested direction") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")
        PressStartUntilOverworld(toolset).invoke()  // bring game to overworld

        val before = toolset.getState().ram
        val sx = before["worldX"] ?: 0
        val sy = before["worldY"] ?: 0

        // Try walking one tile in some direction (DOWN is usually open from Coneria starting pos).
        val result = WalkOverworldTo(toolset).invoke(
            mapOf("targetX" to "$sx", "targetY" to "${sy + 1}", "maxSteps" to "5")
        )
        result.ok.shouldBeTrue()

        val after = toolset.getState().ram
        val ay = after["worldY"] ?: 0
        require(ay == sy + 1 || (after["screenState"] ?: 0) == 0x68) {
            "Did not advance worldY (was $sy, now $ay) and not in battle"
        }
    }
})
