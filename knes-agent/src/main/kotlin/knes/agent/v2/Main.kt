package knes.agent.v2

import knes.agent.llm.AnthropicSession
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.skills.BuyAtShop
import knes.agent.skills.EquipWeapon
import knes.agent.skills.ExitInterior
import knes.agent.skills.RestAtInn
import knes.agent.skills.WalkOverworldTo
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.agents.AdvisorAgent
import knes.agent.v2.agents.CartographerAgent
import knes.agent.v2.agents.ExecutorAgent
import knes.agent.v2.agents.ReviewerAgent
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.llm.SonnetClient
import knes.agent.v2.runtime.Resumer
import knes.agent.v2.runtime.SnapshotDumper
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory
import knes.agent.v2.runtime.Watchdog
import knes.agent.v2.tools.DefaultToolSurface
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val cfg = V2Config.parse(args)
    // TODO(C1): redact key fields from this log line once GeminiPro31Client config lands.
    System.err.println("[v2.main] config=$cfg")
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("ANTHROPIC_API_KEY not set")
    val geminiKey = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("GEMINI_API_KEY not set (Gemini 3.1 Pro required for v2)")

    val run = if (cfg.resumeDir != null) V2RunDirectory.resume(cfg.resumeDir)
             else V2RunDirectory.freshRun()
    System.err.println("[v2.main] run dir: ${run.root}")

    runBlocking {
        AnthropicSession(anthropicKey).use { anthropic ->
            GeminiPro31Client(geminiKey).use { gemini ->
                val session = EmulatorSession()
                val toolset = EmulatorToolset(session)
                require(toolset.loadRom(cfg.rom).ok) { "Failed to load ROM: ${cfg.rom}" }
                require(toolset.applyProfile(cfg.profile).ok) { "Failed to apply profile: ${cfg.profile}" }

                // Perception (shared with v1)
                val overworldMap = OverworldMap.fromRom(File(cfg.rom))
                val fog = FogOfWar()
                val mapSession = MapSession(InteriorMapLoader(File(cfg.rom).readBytes()), fog)
                val landmarks = LandmarkMemory()

                // v2 runtime
                val memory = V2Memory(run)
                if (cfg.resumeDir != null) Resumer(session, run, memory).resume()
                val snapshotDumper = SnapshotDumper(toolset, run)
                val watchdog = Watchdog()

                // Wire macros (v1 skills) — wired as plain instances, see v1 Main for full constructor args
                val walkOverworld = WalkOverworldTo(toolset, overworldMap, fog, knes.agent.pathfinding.ViewportPathfinder(), knes.agent.runtime.ToolCallLog())
                val exitInterior = ExitInterior(toolset, mapSession, fog,
                    knes.agent.pathfinding.InteriorPathfinder(memory = knes.agent.perception.InteriorMemory(), mapIdProvider = { toolset.getState().ram["currentMapId"] ?: -1 }),
                    knes.agent.runtime.ToolCallLog(), knes.agent.perception.InteriorMemory())
                // Macros: v1 skills (BuyAtShop V5.45 vision-advisor, EquipWeapon, RestAtInn).
                // EquipWeapon has a known MenuStuck bug — see spec section 13.
                val tools = DefaultToolSurface(
                    toolset = toolset,
                    phaseProvider = { knes.agent.v2.runtime.Phase.fromRam(toolset.getState().ram) },
                    walkOverworld = walkOverworld,
                    exitInterior = exitInterior,
                    buyAtShopSkill = BuyAtShop(toolset, landmarks),
                    equipWeaponSkill = EquipWeapon(toolset),
                    restAtInnSkill = RestAtInn(toolset),
                )

                // Agents
                val advisor = AdvisorAgent(gemini, memory, run)
                val executor = ExecutorAgent(anthropic, SonnetClient(anthropic), tools, memory)
                val reviewer = ReviewerAgent(HaikuClient(anthropic), memory)
                val cartographer = CartographerAgent(
                    gemini, toolset, memory, snapshotDumper, overworldMap, fog, landmarks,
                    cfg.cartographerBudgetSeconds, cfg.cartographerMaxVisionCalls,
                )

                System.err.println("[v2.main] bootstrap complete — campaign loop in D2")
            }
        }
    }
}
