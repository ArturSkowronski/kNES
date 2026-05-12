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

                // Phase 0: bootstrap
                val firstTurn = memory.campaign.lastTurn + 1
                if (cfg.fresh) {
                    // Pre-cartographer: deterministically press through title → party creation → overworld.
                    // Cartographer needs a party sprite on-screen to do locate-party-first vision.
                    System.err.println("[v2.main] pressStartUntilOverworld …")
                    val bootResult = knes.agent.skills.PressStartUntilOverworld(toolset)
                        .invoke(mapOf("maxAttempts" to "60"))
                    System.err.println("[v2.main] boot → ${bootResult.message}")
                    require(bootResult.ok) { "PressStartUntilOverworld failed: ${bootResult.message}" }
                    cartographer.exploreInitialOverworld()
                    snapshotDumper.dump(0)
                    val snap0 = toolset.getScreen().base64
                    advisor.plan(reason = "T0 fresh campaign", screenshotB64 = snap0, turn = firstTurn)
                } else {
                    val snap = toolset.getScreen().base64
                    advisor.plan(reason = "resume context", screenshotB64 = snap, turn = firstTurn)
                }

                // Phase 1: campaign loop
                val recentExecutorOutcomes = ArrayDeque<String>(4)
                var turn = firstTurn
                while (turn <= cfg.maxTurns && !memory.campaign.done) {
                    val snap = toolset.getScreen().base64
                    snapshotDumper.dump(turn)
                    val state = toolset.getState()
                    val phase = knes.agent.v2.runtime.Phase.fromRam(state.ram)
                    val ramDigest = state.ram.entries.joinToString(",") { "${it.key}=${it.value}" }

                    val decision = executor.act(screenshotB64 = snap, ramDigest = ramDigest)

                    val outcomeLabel = decision.outcome.javaClass.simpleName
                    recentExecutorOutcomes.addLast(outcomeLabel)
                    if (recentExecutorOutcomes.size > 4) recentExecutorOutcomes.removeFirst()

                    val ramHash = state.ram.values.fold(0) { acc, v -> 31 * acc + v }
                    val skillProgress = decision.outcome is knes.agent.v2.tools.ToolOutcome.Ok
                    watchdog.observe(phase, ramHash, skillProgress)

                    memory.appendTurn(
                        knes.agent.v2.runtime.TurnLog(
                            turn = turn,
                            frame = state.frame.toLong(),
                            phase = phase.name,
                            ram = state.ram,
                            snapshot = "snapshots/turn-%05d.png".format(turn),
                            executor = knes.agent.v2.runtime.ExecutorTrace(
                                model = SonnetClient(anthropic).modelId,
                                tool = decision.tool,
                                args = decision.args,
                                reasoningSummary = decision.reasoning,
                                outcome = outcomeLabel.lowercase(),
                                message = (decision.outcome as? knes.agent.v2.tools.ToolOutcome.Ok)?.message
                                    ?: (decision.outcome as? knes.agent.v2.tools.ToolOutcome.Fail)?.message
                                    ?: (decision.outcome as? knes.agent.v2.tools.ToolOutcome.Reject)?.reason,
                                ms = decision.ms,
                            ),
                            watchdog = knes.agent.v2.runtime.WatchdogTrace(
                                stuckCounter = watchdog.counter(),
                                threshold = watchdog.threshold(phase),
                            ),
                        )
                    )

                    if (watchdog.stuckSignal(phase)) {
                        System.err.println("[v2.main] stuck-signal — ${watchdog.diagnose(phase, recentExecutorOutcomes.toList())}")
                        advisor.plan(
                            reason = "stuck: ${watchdog.diagnose(phase, recentExecutorOutcomes.toList())}",
                            screenshotB64 = snap, turn = turn,
                        )
                        watchdog.reset()
                    }

                    if (turn % 50 == 0) {
                        reviewer.audit(turn)
                    }

                    if (turn % 100 == 0) {
                        val saveBytes = session.saveState()
                        java.nio.file.Files.write(run.savestate(turn), saveBytes)
                        System.err.println("[v2.main] checkpoint saved at T$turn (${saveBytes.size} bytes)")
                    }

                    turn++
                }

                System.err.println("[v2.main] done. last_turn=${memory.campaign.lastTurn}")
            }
        }
    }
}
