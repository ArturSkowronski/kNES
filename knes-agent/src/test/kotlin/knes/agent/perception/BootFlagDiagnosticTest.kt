package knes.agent.perception

import io.kotest.core.spec.style.FunSpec
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * Diagnostic: when does bootFlag (RAM 0x00F9) take its 0x4D value?
 * Phase 3.1's recorder showed bootFlag=0x4D from the first snapshot, which contradicts
 * V1's heuristic that 0x4D means "in-game". This test pinpoints exactly when the value
 * appears.
 */
class BootFlagDiagnosticTest : FunSpec({
    test("bootFlag timeline from cold boot") {
        val rom = "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")

        fun read(label: String) {
            val ram = toolset.getState().ram
            val frame = toolset.getState().frame
            println("[diag] frame=$frame label=$label  bootFlag=0x${(ram["bootFlag"] ?: -1).toString(16)}  screenState=0x${(ram["screenState"] ?: -1).toString(16)}  menuCursor=0x${(ram["menuCursor"] ?: -1).toString(16)}")
        }

        read("immediately after loadRom + applyProfile")
        toolset.step(buttons = emptyList(), frames = 1)
        read("after 1 frame")
        toolset.step(buttons = emptyList(), frames = 9)
        read("after 10 frames")
        toolset.step(buttons = emptyList(), frames = 50)
        read("after 60 frames")
        toolset.step(buttons = emptyList(), frames = 60)
        read("after 120 frames")
        toolset.step(buttons = emptyList(), frames = 120)
        read("after 240 frames")
        toolset.step(buttons = emptyList(), frames = 360)
        read("after 600 frames (10 sec)")
    }
})
