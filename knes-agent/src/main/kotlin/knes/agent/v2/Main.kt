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
import knes.agent.v2.runtime.Log
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
    Log.main("run dir: ${run.root}")

    runBlocking {
        AnthropicSession(anthropicKey).use { anthropic ->
            AnthropicHttp(anthropicKey).use { anthropicHttp ->
            // Two Gemini clients: Pro for Advisor + Cartographer + Executor.
            // We previously defaulted the Executor to gemini-3.1-flash-lite
            // for latency (5-10× faster), but Flash-Lite mis-identified
            // building positions in the town viewport (called the centre
            // path "near the Inn", walked Right off the south edge, then
            // confused Coneria Castle for Coneria Town). Pro reads the
            // scene reliably and the per-turn cost is acceptable. Override
            // via GEMINI_MODEL (both) or GEMINI_EXECUTOR_MODEL.
            val executorModel = System.getenv("GEMINI_EXECUTOR_MODEL")?.takeIf { it.isNotBlank() }
                ?: "gemini-3.1-pro-preview"
            GeminiPro31Client(geminiKey).use { gemini ->
            GeminiPro31Client(geminiKey, modelOverride = executorModel).use { geminiExec ->
                // Toolset: in-process NES by default, REST-driven remote
                // (talking to the Compose UI's embedded API server) when
                // `--remote=<url>` is set. The remote variant skips loadRom
                // (the UI loads the ROM) and savestate checkpoints (no
                // /save endpoint yet).
                val session = EmulatorSession()
                val toolset: EmulatorToolset = if (cfg.remoteUrl != null) {
                    Log.event("REMOTE mode — RemoteEmulatorToolset → ${cfg.remoteUrl} (ROM must be loaded in UI; /save checkpoints skipped)")
                    knes.agent.tools.RemoteEmulatorToolset(cfg.remoteUrl, session)
                } else {
                    EmulatorToolset(session)
                }
                if (cfg.remoteUrl == null) {
                    require(toolset.loadRom(cfg.rom).ok) { "Failed to load ROM: ${cfg.rom}" }
                }
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
                if (cfg.resumeDir != null) {
                    require(cfg.remoteUrl == null) {
                        "--resume is not supported with --remote (no save/load over REST yet)"
                    }
                    Resumer(session, run, memory).resume()
                }
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
                Log.llm("models: advisor/cart=${gemini.model} executor=${geminiExec.model}")
                val reviewer = ReviewerAgent(haiku, memory, run)
                val cartographer = CartographerAgent(
                    gemini, toolset, memory, snapshotDumper, overworldMap, fog, landmarks,
                    cfg.cartographerBudgetSeconds, cfg.cartographerMaxVisionCalls,
                    run,
                )

                Log.main("bootstrap complete — entering campaign loop")

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
                    Log.main("pre-cart boot: pressing START to leave title")
                    pressStart.invoke(emptyMap())
                    waitForStableFrame(toolset)
                    if (cfg.cartographerEnabled) {
                        cartographer.exploreInitialOverworld()
                    } else {
                        Log.cartographer("SKIPPED (use --cart to enable). Using preseeded landmarks.")
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
                // Gold-bleed detector: track last-turn gold and the tool that
                // executed. If gold drops while the tool is plain `sequence`
                // (not buyAtShop/restAtInn) the agent has accidentally engaged
                // an inn/save NPC and is paying 30G per Yes loop. We cancel
                // with B-mash and force an Advisor replan with explicit cause.
                // NOTE: the gold drop observed at turn N is caused by the
                // action taken in turn N-1 (the buy confirms during the
                // post-A settle frames before turn N's snapshot). So the
                // attribution must compare prevTool, not the current tool.
                var prevGold: Int? = null
                var prevTool: String? = null
                // Reviewer.auditPlan (every 25 turns) needs hysteresis — a
                // freshly-issued plan in mid-execution will trip the audit
                // ("agent at (9,16), target (11,10)") on the first hit and
                // get replanned away. Require 2 consecutive audits with
                // issues before triggering a replan. Reset on milestone
                // advance.
                var consecutiveAuditHits = 0
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
                    // Per-turn human-readable decision line — the headline
                    // signal during the talk. Shows what the agent JUST
                    // decided + outcome + first ~80 chars of LLM reasoning.
                    Log.turn(
                        turn = turn,
                        phase = phase.name,
                        smX = state.ram["smPlayerX"],
                        smY = state.ram["smPlayerY"],
                        tool = decision.tool,
                        args = decision.args,
                        outcome = outcomeLabel.lowercase(),
                        message = when (val o = decision.outcome) {
                            is ToolOutcome.Ok -> o.message
                            is ToolOutcome.Fail -> o.message
                            is ToolOutcome.Reject -> o.reason
                        },
                        reasoning = decision.reasoning,
                    )
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

                    // Gold-bleed detection — see prevGold comment above.
                    val curGold = (state.ram["goldLow"] ?: 0) +
                        ((state.ram["goldMid"] ?: 0) shl 8) +
                        ((state.ram["goldHigh"] ?: 0) shl 16)
                    // Attribute the gold drop to the tool from the PREVIOUS
                    // turn (the action that actually caused it). Current
                    // turn's tool is just the next decision being made.
                    val attributedTool = prevTool ?: decision.tool
                    val intentionalSpendTool = attributedTool in setOf("buyAtShop", "restAtInn", "useMenu")
                    val pg = prevGold
                    if (pg != null && curGold < pg && !intentionalSpendTool) {
                        val delta = pg - curGold
                        // Plausible-shopping suppression: during buy_weapons
                        // OR arm_party (the buy can latch buy_weapons mid-
                        // turn and the next turn already shows arm_party as
                        // the current milestone), with a sequence-driven
                        // buy menu, weapon purchases legitimately drop gold
                        // by Coneria shop amounts (5G, 10G, multiples).
                        // Don't replan those — they are the intended flow
                        // now that buyAtShop is gone. The inn loop bug
                        // drops 30G per Yes — we still want to catch that
                        // when the agent isn't supposed to be shopping.
                        val currentMs = memory.campaign.milestones
                            .firstOrNull { it.status == "in_progress" }?.id
                        val knownBuyAmounts = setOf(5, 10, 15, 20, 25, 30, 35, 40)
                        val shoppingMilestone = currentMs in setOf("buy_weapons", "arm_party", "enter_weapon_shop")
                        val plausibleShopping = shoppingMilestone &&
                            attributedTool == "sequence" &&
                            delta in knownBuyAmounts
                        if (plausibleShopping) {
                            Log.event("gold drop −${delta}G during $currentMs + sequence (attributed=$attributedTool) — plausible weapon purchase, no replan", turn)
                        } else {
                            Log.warn("GOLD BLEED $pg → $curGold (−${delta}G) without intentional spend (attributed=$attributedTool, current=${decision.tool}, ms=$currentMs). B-mash + replan.", turn)
                            // Mash B six times to back out of any open Yes/No or
                            // shop overlay before the next snapshot.
                            repeat(6) {
                                toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 8)
                                toolset.step(buttons = emptyList(), frames = 12)
                            }
                            advisor.plan(
                                reason = "OBSERVATION: gold dropped -${delta}G at sm=(${state.ram["smPlayerX"]},${state.ram["smPlayerY"]}) while last tool was `${decision.tool}` (no intentional spend). Cause unknown — could be accidental NPC dialog (Yes/No), an inn-stay, a misfired skill, or a legitimate cost we didn't model. Inspect the current screenshot to identify which building/NPC the party is adjacent to (sign text, counter contents) and decide whether to retry, back out, or continue. Do NOT assume it was an inn unless the screenshot confirms a bed/INN sign.",
                                screenshotB64 = snap, turn = turn,
                                phase = phase, ram = state.ram,
                            )
                        }
                    }
                    prevGold = curGold
                    prevTool = decision.tool

                    val milestoneJustAdvanced = advanceMilestones(memory, phase, state.ram, decision, turn)
                    if (milestoneJustAdvanced != null) {
                        val advisorReason = "milestone $milestoneJustAdvanced just done — replan for next"
                        Log.event(advisorReason, turn)
                        // Fresh plan should get a full grace period from
                        // the audit-hysteresis counter.
                        consecutiveAuditHits = 0
                        advisor.plan(
                            reason = advisorReason, screenshotB64 = snap, turn = turn,
                            phase = phase, ram = state.ram,
                        )
                    }

                    if (watchdog.stuckSignal(phase)) {
                        val diag = watchdog.diagnose(phase, recentExecutorOutcomes.toList())
                        Log.warn("stuck-signal — $diag", turn)
                        advisor.plan(
                            reason = "stuck: $diag", screenshotB64 = snap, turn = turn,
                            phase = phase, ram = state.ram,
                        )
                        watchdog.reset()
                    }

                    if (turn % 50 == 0) reviewer.audit(turn)

                    // LLM-driven plan audit — every 25 turns Haiku checks whether
                    // the Advisor's "done" steps actually happened (gold drop,
                    // weapon equipped, party at target, etc). Hysteresis: a
                    // single audit hit is usually a transient mid-execution
                    // observation ("agent at (9,16), target (11,10) — still
                    // walking"). Only replan if TWO consecutive audits flag
                    // issues, i.e. the plan has been stuck for ~50 turns.
                    // Counter resets on milestone advance.
                    if (turn % 25 == 0 && turn > 0) {
                        val issues = reviewer.auditPlan(turn, snap, ramDigest)
                        if (issues.isNotEmpty()) {
                            consecutiveAuditHits += 1
                            if (consecutiveAuditHits >= 2) {
                                Log.reviewer("auditPlan hits $consecutiveAuditHits consecutive — replanning", turn)
                                advisor.plan(
                                    reason = "Reviewer audit (2 consecutive hits): ${issues.joinToString(" | ").take(160)}",
                                    screenshotB64 = snap, turn = turn,
                                    phase = phase, ram = state.ram,
                                )
                                consecutiveAuditHits = 0
                            } else {
                                Log.reviewer("auditPlan issue (1st hit — grace 25t): ${issues.joinToString(" | ").take(120)}", turn)
                            }
                        } else {
                            consecutiveAuditHits = 0
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
                        // Stuck-progress detector — catches the case where
                        // the current milestone has been in_progress for
                        // many turns without satisfying its predicate, and
                        // the plan has no further steps (sentinel intentTool
                        // parking the cursor). Reviewer composes a RAM-aware
                        // diagnosis (party weapon digest, gold, etc.) and
                        // we forward it to the Advisor as a replan reason.
                        // Internal cooldown prevents replan storms.
                        val stuck = reviewer.checkProgress(turn, state.ram)
                        if (stuck != null) {
                            consecutiveAuditHits = 0  // fresh plan deserves a clean audit window
                            advisor.plan(
                                reason = stuck.reason,
                                screenshotB64 = snap, turn = turn,
                                phase = phase, ram = state.ram,
                            )
                        }
                    }

                    if (turn % 100 == 0 && cfg.remoteUrl == null) {
                        // Skip checkpoints in remote mode — the in-process
                        // `session` isn't driving the emulator, so its
                        // saveState() snapshot would be garbage (an empty
                        // NES at boot state, not the Compose UI's frame).
                        val saveBytes = session.saveState()
                        Files.write(run.savestate(turn), saveBytes)
                        Log.ok("checkpoint saved (${saveBytes.size} bytes)", turn)
                    }

                    turn++
                }

                Log.main("done. last_turn=${memory.campaign.lastTurn}")
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
            Log.ok("milestone ★ $id  done; next=${next?.id ?: "(none)"}", turn)
            advancedId = id
        }
    }
    // Single source of truth — see knes.agent.v2.runtime.MilestonePredicates.
    //
    // AT MOST ONE LATCH PER TURN. If multiple predicates already hold
    // (common in --remote mode where the Compose UI inherited weapons/
    // position from a previous run), promoting them all in the same
    // turn would cascade enter_coneria → enter_weapon_shop → buy_weapons
    // → arm_party in a single frame, which is hard to follow on stage
    // and skips the Advisor replan hook that should fire per advance.
    // Instead: latch the FIRST currently-in_progress milestone whose
    // predicate matches, promote the next pending to in_progress, and
    // BREAK. The newly-promoted one can latch next turn at the earliest.
    val prereqDone = ms.associate { it.id to (it.status == "done") }
    for (m in ms) {
        if (m.status != "in_progress") continue
        if (knes.agent.v2.runtime.MilestonePredicates.evaluate(m.id, phase, ram, prereqDone)) {
            mark(m.id) { true }
            break
        }
    }

    memory.saveCampaign()
    return advancedId
}
