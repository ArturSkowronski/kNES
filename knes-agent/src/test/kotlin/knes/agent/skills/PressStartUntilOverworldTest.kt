package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class PressStartUntilOverworldTest : FunSpec({
    test("advances bootFlag to 0x4D from a fresh boot") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test  // skip when ROM unavailable on CI

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom).ok shouldBe true
        toolset.applyProfile("ff1").ok shouldBe true

        val result = PressStartUntilOverworld(toolset).invoke()

        result.ok.shouldBeTrue()
        result.ramAfter["bootFlag"] shouldBe 0x4D
    }
})
