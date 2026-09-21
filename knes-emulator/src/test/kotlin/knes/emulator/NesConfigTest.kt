package knes.emulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import knes.emulator.input.InputHandler
import knes.emulator.utils.Globals
import knes.emulator.utils.HiResTimer

private fun host() = object : NesHost {
    override fun sendErrorMsg(message: String) {}
    override fun sendDebugMessage(message: String) {}
    override fun destroy() {}
    override fun getJoy1(): InputHandler = object : InputHandler {
        override fun getKeyState(padKey: Int): Short = 0x40
    }
    override fun getJoy2(): InputHandler? = null
    override fun getTimer(): HiResTimer = HiResTimer()
    override fun imageReady(skipFrame: Boolean, buffer: IntArray) {}
}

class NesConfigTest : FunSpec({

    test("two emulators in one JVM can disagree about region and sound") {
        // The whole point of moving these off Globals: this used to be impossible.
        val ntsc = NES(host(), NesConfig(palEmulation = false, enableSound = false))
        val pal = NES(host(), NesConfig(palEmulation = true, enableSound = true))

        ntsc.config.palEmulation shouldBe false
        pal.config.palEmulation shouldBe true
        ntsc.config.enableSound shouldBe false
        pal.config.enableSound shouldBe true
    }

    test("one emulator turning its sound off does not silence another") {
        val a = NES(host(), NesConfig(enableSound = true))
        val b = NES(host(), NesConfig(enableSound = true))

        // The APU does exactly this when the audio device fails to open.
        a.config.enableSound = false

        b.config.enableSound shouldBe true
    }

    test("frame time follows the configured frame rate") {
        NesConfig(preferredFrameRate = 60).frameTime shouldBe 16666
        NesConfig(preferredFrameRate = 50).frameTime shouldBe 20000
    }

    test("the headless preset runs without audio or frame pacing") {
        NesConfig.HEADLESS.enableSound shouldBe false
        NesConfig.HEADLESS.timeEmulation shouldBe false
    }

    test("a host that passes no config still gets the legacy singleton") {
        // Desktop UIs configure through Globals and must keep working.
        val previous = Globals.palEmulation
        try {
            Globals.palEmulation = true
            NES(host()).config.palEmulation shouldBe true
        } finally {
            Globals.palEmulation = previous
        }
    }

    test("config is a value, so reading it back cannot mutate the emulator") {
        val nes = NES(host(), NesConfig(preferredFrameRate = 50))
        val copy = nes.config.copy(preferredFrameRate = 30)

        copy shouldNotBe nes.config
        nes.config.preferredFrameRate shouldBe 50
    }

    test("every emulator clocks its own PPU, so frames need no configuring") {
        // There used to be a flag deciding whether the CPU loop drives the PPU, which
        // meant the same config produced frames or did not depending on who owned the
        // loop. C3c and C3d removed the choice.
        val nes = NES(host(), NesConfig.HEADLESS)
        nes.loadRom(java.io.File("src/test/resources/nestest.nes").absolutePath) shouldBe true

        repeat(20_000) { nes.stepInstruction() }

        (nes.frameCount > 0) shouldBe true
    }
})
