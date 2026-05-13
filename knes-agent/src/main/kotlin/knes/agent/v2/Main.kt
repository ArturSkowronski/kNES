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
import knes.agent.skills.PressStartUntilOverworld
import knes.agent.skills.RestAtInn
import knes.agent.skills.WalkOverworldTo
import knes.agent.tools.EmulatorToolset
import knes.agent.v2.agents.AdvisorAgent
import knes.agent.v2.agents.CartographerAgent
import knes.agent.v2.agents.ExecutorAgent
import knes.agent.v2.agents.ReviewerAgent
import knes.agent.v2.llm.AnthropicHttp
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.llm.SonnetClient
import knes.agent.v2.runtime.Phase
import knes.agent.v2.runtime.SnapshotDumper
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory
import knes.agent.v2.runtime.Watchdog
import knes.agent.v2.runtime.ExecutorTrace
import knes.agent.v2.runtime.Resumer
import knes.agent.v2.runtime.TurnLog
import knes.agent.v2.runtime.WatchdogTrace
import knes.agent.v2.tools.DefaultToolSurface
import knes.agent.v2.tools.ToolOutcome
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

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
            AnthropicHttp(anthropicKey).use { anthropicHttp ->
            // Two Gemini clients: Pro for thinking-heavy Advisor + Cartographer,
            // Flash-lite for the per-turn Executor (5-10× lower latency on
            // gemini-3.1-flash-lite vs gemini-3.1-pro-preview; Executor only
            // emits a single button or one tool call so it does not need full
            // thinking). Override via GEMINI_MODEL (both) or GEMINI_EXECUTOR_MODEL.
            val executorModel = System.getenv("GEMINI_EXECUTOR_MODEL")?.takeIf { it.isNotBlank() }
                ?: "gemini-3.1-flash-lite"
            GeminiPro31Client(geminiKey).use { gemini ->
            GeminiPro31Client(geminiKey, modelOverride = executorModel).use { geminiExec ->
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
                if (cfg.resumeDir != null) Resumer(session, run, memory).resume()
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
                val pressStart = PressStartUntilOverworld(toolset)

                val sonnet = SonnetClient(anthropicHttp)
                val haiku = HaikuClient(anthropicHttp)
                val tools = DefaultToolSurface(
                    toolset = toolset,
                    phaseProvider = { Phase.fromRam(toolset.getState().ram) },
                    pressStartUntilOverworld = pressStart,
                    walkOverworld = walkOverworld,
                    exitInterior = exitInterior,
                    buyAtShopSkill = buyAtShop,
                    equipWeaponSkill = equipWeapon,
                    restAtInnSkill = restAtInn,
                    haiku = haiku,
                    livePngFile = run.liveSnapshot,
                )

                // Agents
                val advisor = AdvisorAgent(gemini, memory, run, landmarks)
                val executor = ExecutorAgent(anthropic, sonnet, haiku, tools, memory, run, gemini = geminiExec)
                System.err.println("[v2.main] LLM models: advisor/cart=${gemini.model} executor=${geminiExec.model}")
                val reviewer = ReviewerAgent(haiku, memory, run)
                val cartographer = CartographerAgent(
                    gemini, toolset, memory, snapshotDumper, overworldMap, fog, landmarks,
                    cfg.cartographerBudgetSeconds, cfg.cartographerMaxVisionCalls,
                    run,
                )

                System.err.println("[v2.main] bootstrap complete — entering campaign loop")

                // Phase 0: bootstrap (boot → Cartographer → first Advisor plan).
                // CRITICAL ORDERING: boot MUST run before Cartographer. Otherwise
                // Cartographer's first iter reads worldX=worldY=0 (title screen
                // RAM) and asks Gemini for a direction looking at the title.
                // Gemini answers "DONE" immediately and Cartographer logs "1
                // vision calls, 0 steps" — landmarks come only from preseed,
                // explore-the-world is pure waste. Pressing START first lands
                // us on Overworld with a real worldX/worldY before cart sees
                // anything.
                val firstTurn = memory.campaign.lastTurn + 1
                if (cfg.fresh) {
                    System.err.println("[v2.main] pre-cart boot: pressing START to leave title")
                    pressStart.invoke(emptyMap())
                    waitForStableFrame(toolset)
                    if (cfg.cartographerEnabled) {
                        cartographer.exploreInitialOverworld()
                    } else {
                        System.err.println("[v2.main] cartographer SKIPPED (use --cart to enable). Using preseeded landmarks.")
                    }
                    waitForStableFrame(toolset)
                    snapshotDumper.dump(0)
                    val snap0 = toolset.getScreen().base64
                    val s0 = toolset.getState()
                    advisor.plan(
                        reason = "T0 fresh campaign", screenshotB64 = snap0, turn = firstTurn,
                        phase = Phase.fromRam(s0.ram), ram = s0.ram,
                    )
                } else {
                    waitForStableFrame(toolset)
                    val snap = toolset.getScreen().base64
                    val s = toolset.getState()
                    advisor.plan(
                        reason = "resume context", screenshotB64 = snap, turn = firstTurn,
                        phase = Phase.fromRam(s.ram), ram = s.ram,
                    )
                }

                // Phase 1: campaign loop
                val recentExecutorOutcomes = ArrayDeque<String>(4)
                val sonnetModelId = sonnet.modelId
                var turn = firstTurn
                while (turn <= cfg.maxTurns && !memory.campaign.done) {
                    // Settle transition transients before vision/RAM sampling. Without
                    // this, snapshots can land mid-scroll/mid-map-load (the screen
                    // appears with black bars top/bottom) and RAM shows transient
                    // mapflags.bit1=1 + mid-frame coords — Haiku then sees a partial
                    // viewport and the Executor decides on bogus state.
                    waitForStableFrame(toolset)
                    val snap = toolset.getScreen().base64
                    snapshotDumper.dump(turn)
                    val state = toolset.getState()
                    val phase = Phase.fromRam(state.ram)
                    val ramDigest = state.ram.entries.joinToString(",") { "${it.key}=${it.value}" }

                    val decision = executor.act(screenshotB64 = snap, ramDigest = ramDigest, turn = turn)

                    val outcomeLabel = decision.outcome.javaClass.simpleName
                    recentExecutorOutcomes.addLast(outcomeLabel)
                    if (recentExecutorOutcomes.size > 4) recentExecutorOutcomes.removeFirst()

                    val ramHash = state.ram.values.fold(0) { acc, v -> 31 * acc + v }
                    val skillProgress = decision.outcome is ToolOutcome.Ok
                    watchdog.observe(phase, ramHash, skillProgress)

                    memory.appendTurn(
                        TurnLog(
                            turn = turn, frame = state.frame.toLong(), phase = phase.name,
                            ram = state.ram,
                            snapshot = "snapshots/turn-%05d.png".format(turn),
                            executor = ExecutorTrace(
                                model = sonnetModelId,
                                tool = decision.tool, args = decision.args,
                                reasoningSummary = decision.reasoning,
                                outcome = outcomeLabel.lowercase(),
                                message = when (val o = decision.outcome) {
                                    is ToolOutcome.Ok -> o.message
                                    is ToolOutcome.Fail -> o.message
                                    is ToolOutcome.Reject -> o.reason
                                },
                                ms = decision.ms,
                            ),
                            watchdog = WatchdogTrace(
                                stuckCounter = watchdog.counter(), threshold = watchdog.threshold(phase),
                            ),
                        )
                    )

                    val milestoneJustAdvanced = advanceMilestones(memory, phase, state.ram, decision, turn)
                    if (milestoneJustAdvanced != null) {
                        val advisorReason = "milestone $milestoneJustAdvanced just done — replan for next"
                        System.err.println("[v2.main] $advisorReason")
                        advisor.plan(
                            reason = advisorReason, screenshotB64 = snap, turn = turn,
                            phase = phase, ram = state.ram,
                        )
                    }

                    if (watchdog.stuckSignal(phase)) {
                        val diag = watchdog.diagnose(phase, recentExecutorOutcomes.toList())
                        System.err.println("[v2.main] stuck-signal — $diag")
                        advisor.plan(
                            reason = "stuck: $diag", screenshotB64 = snap, turn = turn,
                            phase = phase, ram = state.ram,
                        )
                        watchdog.reset()
                    }

                    if (turn % 50 == 0) reviewer.audit(turn)

                    // LLM-driven plan audit — every 25 turns Haiku checks whether
                    // the Advisor's "done" steps actually happened (gold drop,
                    // weapon equipped, party at target, etc). On reported issues
                    // we trigger an Advisor replan with the diagnostic.
                    if (turn % 25 == 0 && turn > 0) {
                        val issues = reviewer.auditPlan(turn, snap, ramDigest)
                        if (issues.isNotEmpty()) {
                            advisor.plan(
                                reason = "Reviewer audit found issues: ${issues.joinToString(" | ").take(160)}",
                                screenshotB64 = snap, turn = turn,
                                phase = phase, ram = state.ram,
                            )
                        }
                    }

                    // Deterministic milestone verifier — every 10 turns, re-check
                    // each "done" milestone against current RAM. On regression,
                    // revert to in_progress + trigger Advisor replan with the
                    // diagnostic. Cheap (no LLM); catches false-positive latches
                    // and savestate-induced state drops.
                    if (turn % 10 == 0) {
                        val regressed = reviewer.verifyMilestones(phase, state.ram, turn)
                        if (regressed.isNotEmpty()) {
                            advisor.plan(
                                reason = "milestone REGRESSED: ${regressed.joinToString(",")}",
                                screenshotB64 = snap, turn = turn,
                                phase = phase, ram = state.ram,
                            )
                        }
                    }

                    if (turn % 100 == 0) {
                        val saveBytes = session.saveState()
                        Files.write(run.savestate(turn), saveBytes)
                        System.err.println("[v2.main] checkpoint saved at T$turn (${saveBytes.size} bytes)")
                    }

                    turn++
                }

                System.err.println("[v2.main] done. last_turn=${memory.campaign.lastTurn}")
            }
            }
            }
        }
    }
}

/**
 * Advance campaign milestones based on observed phase + RAM signals. Idempotent:
 * each milestone flips from "in_progress" to "done" exactly once, and the next
 * pending milestone becomes "in_progress". Without this, `currentMilestone()`
 * falls through to "boot" forever and the Advisor keeps emitting "Restart with
 * default party" (Smoke 1 v2 evidence: 20 replans in 109 turns all saying that).
 *
 * Signals (deliberately coarse — refinement deferred until a milestone earns it):
 *  - boot          → done when phase != Boot (party + worldX present)
 *  - enter_coneria → done when phase == Town (mapflags.bit0=1, mapId=0)
 *  - arm_party     → done when ALL 4 chars have ≥1 weapon equipped (bit7 set)
 *  - exit_coneria  → done when phase == Overworld AFTER enter_coneria fired
 *  - grind         → done when any char's xpLow > 0 (post-battle XP gained)
 */
/**
 * Block until the next stable frame: mapflags.bit1=0 (no dialog/transition
 * transient) AND scrolling=0. Otherwise vision sees a half-drawn viewport and
 * RAM reports mid-step coords. Polls in 30-frame chunks up to [maxPolls] times
 * (~120 frames = ~2s wallclock at full speed). Idempotent if already stable.
 *
 * Empirical: snapshot at T7 in 2026-05-12-2120 run showed a chunked overworld
 * frame (black bars top/bottom) because the screenshot was taken while the
 * engine was mid-map-load — agent's Haiku then read garbage scene.
 */
private suspend fun waitForStableFrame(
    toolset: EmulatorToolset, maxPolls: Int = 8,
) {
    repeat(maxPolls) {
        val r = toolset.getState().ram
        val mfBit1 = (r["mapflags"] ?: 0) and 0x02
        val scrolling = r["scrolling"] ?: 0
        if (mfBit1 == 0 && scrolling == 0) return
        toolset.step(buttons = emptyList(), frames = 30)
    }
}

private fun advanceMilestones(
    memory: knes.agent.v2.runtime.V2Memory,
    phase: knes.agent.v2.runtime.Phase,
    ram: Map<String, Int>,
    @Suppress("UNUSED_PARAMETER") decision: knes.agent.v2.agents.ExecutorDecision,
    turn: Int,
): String? {
    val ms = memory.campaign.milestones
    var advancedId: String? = null
    fun mark(id: String, predicate: () -> Boolean) {
        val m = ms.firstOrNull { it.id == id } ?: return
        if (m.status == "in_progress" && predicate()) {
            m.status = "done"; m.turnEnd = turn
            val next = ms.firstOrNull { it.status == "pending" }
            if (next != null) { next.status = "in_progress"; next.turnStart = turn }
            System.err.println("[v2.main] milestone $id done at T$turn; next=${next?.id ?: "(none)"}")
            advancedId = id
        }
    }
    // Single source of truth — see knes.agent.v2.runtime.MilestonePredicates.
    val prereqDone = ms.associate { it.id to (it.status == "done") }
    for (m in ms) {
        if (m.status != "in_progress") continue
        if (knes.agent.v2.runtime.MilestonePredicates.evaluate(m.id, phase, ram, prereqDone)) {
            mark(m.id) { true }
        }
    }

    memory.saveCampaign()
    return advancedId
}
