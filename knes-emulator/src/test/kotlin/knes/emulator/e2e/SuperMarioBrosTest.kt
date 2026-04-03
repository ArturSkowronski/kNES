package knes.emulator.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldNotBe
import knes.emulator.input.InputHandler

class SuperMarioBrosTest : FunSpec({

    val romPath = EmulatorTestHarness.findSmb()

    test("title screen transitions to gameplay when Start is pressed") {
        if (romPath == null) {
            throw io.kotest.engine.TestAbortedException(
                "SMB ROM not found. Set KNES_TEST_ROM_SMB env var or place ROM at roms/smb.nes"
            )
        }

        val h = EmulatorTestHarness(romPath)

        // Wait for title screen to load
        h.advanceFrames(120)

        // Read game engine state during title screen
        val titleState = h.readMemory(0x0770)

        // Press Start to begin the game
        h.pressButton(InputHandler.KEY_START)
        h.advanceFrames(5)
        h.releaseButton(InputHandler.KEY_START)

        // Wait for game to transition to gameplay
        val transitioned = h.advanceUntil(300) {
            h.readMemory(0x0770) != titleState
        }

        transitioned shouldNotBe false
    }

    test("Mario moves right when Right button is held") {
        if (romPath == null) {
            throw io.kotest.engine.TestAbortedException(
                "SMB ROM not found. Set KNES_TEST_ROM_SMB env var or place ROM at roms/smb.nes"
            )
        }

        val h = EmulatorTestHarness(romPath)

        // Navigate past title screen
        h.advanceFrames(120)
        h.pressButton(InputHandler.KEY_START)
        h.advanceFrames(5)
        h.releaseButton(InputHandler.KEY_START)

        // Wait for gameplay to be fully active
        h.advanceFrames(180)

        // Read initial X position
        val initialX = h.readMemory(0x0086)

        // Hold Right for 60 frames (1 second)
        h.pressButton(InputHandler.KEY_RIGHT)
        h.advanceFrames(60)
        h.releaseButton(InputHandler.KEY_RIGHT)

        // Verify Mario moved right
        val finalX = h.readMemory(0x0086)
        finalX shouldBeGreaterThan initialX
    }
})
