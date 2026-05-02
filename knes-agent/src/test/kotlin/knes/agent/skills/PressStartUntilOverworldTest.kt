package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

class PressStartUntilOverworldTest : FunSpec({
    test("advances from cold boot to overworld with party created") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test  // skip when ROM unavailable on CI

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom).ok shouldBe true
        toolset.applyProfile("ff1").ok shouldBe true

        val result = PressStartUntilOverworld(toolset).invoke()

        result.ok.shouldBeTrue()
        result.ramAfter["char1_hpLow"]!! shouldNotBe 0
        result.ramAfter["worldX"]!! shouldNotBe 0
    }
})
