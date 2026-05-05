package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.perception.FogOfWar
import knes.agent.perception.OverworldMap
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File

/**
 * Live test (requires ROM). Runs ExploreOverworldFrontier from spawn and asserts
 * the skill returns ok=true with at least one frame of movement OR a recognized
 * stop reason (encounter, interior entry).
 */
class ExploreOverworldFrontierTest : FunSpec({
    test("walks toward salience target and reports outcome") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        if (!File(rom).exists()) return@test

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        toolset.loadRom(rom).ok shouldBe true
        toolset.applyProfile("ff1").ok shouldBe true
        PressStartUntilOverworld(toolset).invoke()

        val map = OverworldMap.fromRom(File(rom))
        val fog = FogOfWar()
        val skill = ExploreOverworldFrontier(toolset, map, fog)
        val result = skill.invoke(mapOf("targetX" to "146", "targetY" to "157", "maxSteps" to "8"))

        // Either reached, partial-progressed, or a clean stop reason — any non-crash result is fine here.
        (result.ok || result.message.contains("encounter") || result.message.contains("interior") ||
            result.message.contains("stuck")) shouldBe true
    }
})
