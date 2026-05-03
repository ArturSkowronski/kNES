package knes.agent.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
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
        // FF1 V2 boots Indoors (Coneria castle); BFS over the ROM overworld map only
        // makes sense once locationType drops to 0x00. Walk south until outside.
        run {
            val fogTmp = FogOfWar()
            val mapSessionTmp = MapSession(InteriorMapLoader(File(rom).readBytes()), fogTmp)
            ExitBuilding(toolset, mapSessionTmp, fogTmp).invoke()
        }
        // Settle after exiting interior so the screen-transition completes before we
        // start measuring world coords / pressing direction buttons.
        toolset.step(buttons = emptyList(), frames = 60)

        val before = toolset.getState().ram
        val sx = before["worldX"] ?: 0
        val sy = before["worldY"] ?: 0

        val overworldMap = OverworldMap.fromRom(File(rom))
        val fog = FogOfWar()

        // Try walking one tile in some direction (DOWN is usually open from Coneria starting pos).
        val result = WalkOverworldTo(toolset, overworldMap, fog).invoke(
            mapOf("targetX" to "$sx", "targetY" to "${sy + 1}", "maxSteps" to "5")
        )
        require(result.ok) { "WalkOverworldTo failed at sx=$sx sy=$sy: ${result.message}" }

        val after = toolset.getState().ram
        val ay = after["worldY"] ?: 0
        require(ay == sy + 1 || (after["screenState"] ?: 0) == 0x68) {
            "Did not advance worldY (was $sy, now $ay) and not in battle"
        }
    }
})
