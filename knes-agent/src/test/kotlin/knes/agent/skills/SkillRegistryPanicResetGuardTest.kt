package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.string.shouldContain
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * V5.31 panic-reset guard: pressStartUntilOverworld must reject when the party
 * is already created. iter14 evidence: agent called this from Indoors, mashing
 * START+A through the in-game menu and corrupting the run. The guard inverts
 * the same termination markers the skill itself uses.
 */
class SkillRegistryPanicResetGuardTest : FunSpec({
    test("pressStartUntilOverworld rejects when party already created") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom)
        toolset.applyProfile("ff1")
        // Live boot to overworld — sets char1_hpLow != 0 and eventually worldX != 0
        // once the party walks out. char1_hpLow alone is enough to trip the guard.
        PressStartUntilOverworld(toolset).invoke()

        val fog = FogOfWar()
        val mapSession = MapSession(InteriorMapLoader(File(rom).readBytes()), fog)
        val registry = SkillRegistry(
            toolset = toolset,
            overworldMap = OverworldMap.fromRom(File(rom)),
            mapSession = mapSession,
            fog = fog,
        )

        val result = registry.pressStartUntilOverworld()
        result.ok.shouldBeFalse()
        result.message shouldContain "REJECTED"
    }
})
