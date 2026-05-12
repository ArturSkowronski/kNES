package knes.agent.v2

import knes.agent.llm.AnthropicSession
import knes.agent.pathfinding.InteriorPathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.runtime.ToolCallLog
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
import knes.agent.v2.runtime.Phase
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
                val interiorMemory = InteriorMemory()
                val toolCallLog = ToolCallLog()

                // v2 runtime
                val memory = V2Memory(run)
                val snapshotDumper = SnapshotDumper(toolset, run)
                val watchdog = Watchdog()

                // v1 skill instances reused by ToolSurface
                val walkOverworld = WalkOverworldTo(
                    toolset = toolset,
                    viewportSource = overworldMap,
                    fog = fog,
                    pathfinder = ViewportPathfinder(),
                    toolCallLog = toolCallLog,
                )
                val exitInterior = ExitInterior(
                    toolset = toolset,
                    mapSession = mapSession,
                    fog = fog,
                    pathfinder = InteriorPathfinder(memory = interiorMemory) {
                        toolset.getState().ram["currentMapId"] ?: -1
                    },
                    toolCallLog = toolCallLog,
                    interiorMemory = interiorMemory,
                )
                val buyAtShop = BuyAtShop(toolset, landmarks)
                val equipWeapon = EquipWeapon(toolset)
                val restAtInn = RestAtInn(toolset)

                val tools = DefaultToolSurface(
                    toolset = toolset,
                    phaseProvider = { Phase.fromRam(toolset.getState().ram) },
                    walkOverworld = walkOverworld,
                    exitInterior = exitInterior,
                    buyAtShopSkill = buyAtShop,
                    equipWeaponSkill = equipWeapon,
                    restAtInnSkill = restAtInn,
                )

                // Agents
                val advisor = AdvisorAgent(gemini, memory, run)
                val executor = ExecutorAgent(anthropic, SonnetClient(anthropic), tools, memory)
                val reviewer = ReviewerAgent(HaikuClient(anthropic), memory)
                val cartographer = CartographerAgent(
                    gemini, toolset, memory, snapshotDumper, overworldMap, fog, landmarks,
                    cfg.cartographerBudgetSeconds, cfg.cartographerMaxVisionCalls,
                )

                System.err.println("[v2.main] bootstrap complete — campaign loop in D4")
                // Touch every constructed component so unused-warning doesn't
                // hide accidental no-ops during the D2/D4 wire-up.
                System.err.println("[v2.main] agents=${listOf(advisor.javaClass.simpleName,
                    executor.javaClass.simpleName, reviewer.javaClass.simpleName,
                    cartographer.javaClass.simpleName, watchdog.javaClass.simpleName)}")
            }
        }
    }
}
