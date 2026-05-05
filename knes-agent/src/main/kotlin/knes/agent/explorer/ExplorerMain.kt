package knes.agent.explorer

import kotlinx.coroutines.runBlocking
import knes.agent.perception.BlockageMemory
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.perception.OverworldTerrainMemory
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import java.io.File
import kotlin.system.exitProcess

fun main() {
    val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
    val savestate = System.getenv("FF1_SAVESTATE")
        ?: "/Users/askowronski/Priv/kNES-ff1-agent-v2/knes-agent/src/test/resources/fixtures/ff1-post-boot.savestate"

    require(File(rom).exists()) { "ROM not found: $rom (set FF1_ROM)" }
    require(File(savestate).exists()) { "savestate not found: $savestate (set FF1_SAVESTATE)" }

    val session = EmulatorSession()
    val toolset = EmulatorToolset(session)
    require(toolset.loadRom(rom).ok) { "loadRom failed" }
    require(toolset.applyProfile("ff1").ok) { "applyProfile failed" }

    // Read savestate bytes from file — fed into EmulatorSession.loadState() at the start of each run.
    val savedStateBytes = File(savestate).readBytes()

    val romBytes = File(rom).readBytes()
    val fog = FogOfWar()
    val mapSession = MapSession(InteriorMapLoader(romBytes), fog)
    val overworldMap = OverworldMap.fromRom(File(rom))
    val observer = RamObserver(toolset, overworldMap)
    val warpMemory = OverworldWarpMemory()
    val interiorMemory = InteriorMemory()
    val terrainMemory = OverworldTerrainMemory()
    val landmarkMemory = LandmarkMemory()
    val blockageMemory = BlockageMemory()

    val skillRegistry = SkillRegistry(
        toolset = toolset,
        overworldMap = overworldMap,
        mapSession = mapSession,
        fog = fog,
        interiorMemory = interiorMemory,
    )

    val haiku: HaikuConsult = try {
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
        if (apiKey.isNullOrBlank()) {
            System.err.println("[explorer] no ANTHROPIC_API_KEY — using FakeHaikuConsult (zero classification)")
            FakeHaikuConsult()
        } else {
            AnthropicHaikuConsult(apiKey = apiKey)
        }
    } catch (e: Exception) {
        System.err.println("[explorer] Haiku init failed (${e.message}) — using FakeHaikuConsult")
        FakeHaikuConsult()
    }

    val explorer = ExplorerSession(
        toolset = toolset,
        emulatorSession = session,
        observer = observer,
        overworldMap = overworldMap,
        skillRegistry = skillRegistry,
        terrainMemory = terrainMemory,
        landmarkMemory = landmarkMemory,
        blockageMemory = blockageMemory,
        warpMemory = warpMemory,
        interiorMemory = interiorMemory,
        fog = fog,
        haikuConsult = haiku,
        savedState = savedStateBytes,
    )
    val result = runBlocking { explorer.run() }
    println("[campaign] FINAL: $result")
    exitProcess(if (result.outcome == CampaignOutcome.Success) 0 else 1)
}
