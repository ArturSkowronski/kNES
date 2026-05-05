package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.perception.ScreenshotPolicy
import knes.agent.tools.EmulatorToolset
import java.nio.file.Path

data class Budget(
    val maxSkillInvocations: Int = 80,
    val maxAdvisorCalls: Int = 30,
    val costCapUsd: Double = 3.0,
    val wallClockCapSeconds: Int = 900,
)

class AgentSession(
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val executor: ExecutorAgent,
    private val advisor: AdvisorAgent,
    private val toolCallLog: ToolCallLog = ToolCallLog(),
    private val budget: Budget = Budget(),
    /**
     * V5.24: shared FogOfWar so the session can mark warp tiles blocked.
     * BFS pathfinder honors fog blocks → deterministic reroute around
     * known warps. Optional for backward compat with tests.
     */
    private val fog: FogOfWar? = null,
    /**
     * V5.25: persistent overworld warp memory across runs. Default loads
     * from ~/.knes/ff1-overworld-warps.json. Tests pass an in-memory file
     * to keep the host filesystem clean.
     */
    private val warpMemory: OverworldWarpMemory = OverworldWarpMemory(),
    /**
     * Phase 2: persistent landmark catalog populated by the explorer phase
     * (towns, castles, dungeons, NPCs). Injected into both advisor and
     * executor observations so the planner can route to known points of
     * interest without rediscovery. Default loads from ~/.knes/ff1-landmarks.json.
     */
    private val landmarkMemory: LandmarkMemory = LandmarkMemory(),
    runDir: Path = Trace.newRunDir(),
    /**
     * Optional per-turn hook fired after each executor turn. Receives current
     * phase and RAM snapshot. Returning a non-null Outcome short-circuits the
     * loop and returns that outcome. Used by fixture-builder tests to stop
     * the agent the moment a target state is reached (e.g. mapId=8 entered)
     * without modifying the production goal of reaching Garland.
     */
    private val onTurnEnd: ((FfPhase, Map<String, Int>) -> Outcome?)? = null,
) {
    private val resolvedRunDir = runDir
    private val trace = Trace(resolvedRunDir)
    private val screenshotPolicy = ScreenshotPolicy()

    suspend fun run(): Outcome {
        println("[knes-agent] run dir: $resolvedRunDir")
        var previousPhase: FfPhase? = null
        var currentPlan = "Start the game from the title screen and begin a new game."
        var idleTurns = 0
        var lastRam: Map<String, Int> = emptyMap()
        var advisorCalls = 0
        var skillsInvoked = 0
        val startMs = System.currentTimeMillis()
        // V5.23: cross-turn memory of FF1 ROM-encoded warp tiles the agent has tripped
        // into. Both AIAgent instances are spun up fresh each turn (singleRunStrategy +
        // newAgent per phase) so they have NO native memory of prior failures. We track
        // here and inject into both advisor and executor observations so neither suggests
        // routing back through a known warp.
        val failedWarpTiles: MutableSet<Pair<Int, Int>> = mutableSetOf()
        // Match toolCallLog format: "entered interior after N steps; world=(X,Y) ... targeted=false"
        // (set by WalkOverworldTo.kt when an UNEXPECTED interior was entered mid-route).
        val failedRegex = Regex("""world=\((\d+),(\d+)\)[^|]*targeted=false""")
        // V5.25: pre-seed from persistent memory so the very first walk in
        // this run already knows about warps detected in earlier sessions.
        // Both the LLM hint (text injection) and the deterministic fog block
        // are armed before the agent moves.
        val seededWarps = warpMemory.all()
        if (seededWarps.isNotEmpty()) {
            failedWarpTiles += seededWarps
            println("[overworld-warp-memory] preloaded ${seededWarps.size} known warps: $seededWarps")
            fog?.let { f ->
                // V5.28: 1x1 block per warp tile. 3x3 (V5.24) was too aggressive
                // — iter11 evidence: overlapping 3x3 zones around (145,152) and
                // (147,153) sealed the agent into a 1-tile pocket at (145,153).
                // Warps are confirmed 1x1 (each ROM-encoded entry tile is a
                // separate trigger); siblings get auto-detected if also warps.
                seededWarps.forEach { tile -> f.markBlocked(tile.first, tile.second) }
                println("[overworld-warp-memory] fog.markBlocked exact tiles only (1x1)")
            }
        }

        val landmarkContext: String? = LandmarkContext.render(landmarkMemory)
        if (landmarkContext != null) {
            println("[landmark-memory] preloaded ${landmarkMemory.all().size} landmarks (advisor + executor injection)")
        }

        try {
            while (true) {
                val phase = observer.observeWithVision()
                val ram = observer.ramSnapshot()

                val outcome = SuccessCriteria.evaluate(phase)
                if (outcome != Outcome.InProgress) {
                    trace.record(TraceEvent(0, "outcome", phase.toString(), note = outcome.name))
                    return outcome
                }

                // V2.5.6: deterministic PostBattle dismissal. The modal blocks input;
                // until it clears, walkOverworldTo / exitInterior return BLOCKED. The LLM
                // sometimes loops on these instead of calling battleFightAll, burning the
                // budget. Auto-tap A here so the agent never waits on the modal.
                if (phase is FfPhase.PostBattle) {
                    println("[postbattle auto-dismiss]")
                    toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
                    trace.record(TraceEvent(
                        turn = 0, role = "system", phase = phase.toString(),
                        note = "auto-dismissed PostBattle via battle_fight_all",
                    ))
                    continue  // re-observe phase next iteration
                }

                val phaseChanged = previousPhase == null || previousPhase::class != phase::class
                // V4 hybrid C: advisor consult earlier when stuck inside an interior.
                // The decoder is unreliable on towns; vision-by-advisor (not per-step
                // skill) gives cardinal hints without burning vision tokens every step.
                val stuckInInterior = phase is FfPhase.Indoors && idleTurns >= 5
                if (phaseChanged || stuckInInterior || idleTurns >= 20) {
                    if (++advisorCalls > budget.maxAdvisorCalls) return Outcome.OutOfBudget
                    val attachShot = screenshotPolicy.shouldAttach(previousPhase, phase)
                    val reason = when {
                        phaseChanged -> "phase change"
                        stuckInInterior -> "stuck in interior (idle=$idleTurns) — please look at the screen and suggest a cardinal direction"
                        else -> "watchdog stuck (idle=$idleTurns)"
                    }
                    val obs = buildString {
                        append("Phase: $phase\nRAM: $ram\n")
                        if (attachShot) append("(screenshot available via getScreen)\n")
                        if (failedWarpTiles.isNotEmpty()) {
                            // V5.23: session memory injected so the planner reroutes
                            // around tiles that previously warped the party indoors.
                            append("Session memory — known FF1 warp tiles to avoid as " +
                                "targets or route-throughs: ")
                            append(failedWarpTiles.joinToString(", ") { "(${it.first},${it.second})" })
                            append('\n')
                        }
                        if (landmarkContext != null) {
                            append(landmarkContext)
                            append('\n')
                        }
                        append("Reason: $reason")
                    }
                    println("[advisor #$advisorCalls] phase=$phase")
                    currentPlan = advisor.plan(phase, obs)
                    println("[advisor plan] ${currentPlan.lineSequence().take(3).joinToString(" | ").take(200)}")
                    trace.record(
                        TraceEvent(
                            turn = 0, role = "advisor", phase = phase.toString(),
                            input = obs,           // full observation given to advisor
                            output = currentPlan,  // full advisor reasoning, untruncated
                        )
                    )
                    idleTurns = 0
                }

                val executorInput = buildString {
                    append("Plan:\n$currentPlan\n\nCurrent phase: $phase\nRAM: $ram")
                    if (failedWarpTiles.isNotEmpty()) {
                        append("\nSession memory — known FF1 warp tiles to avoid as targets " +
                            "or route-throughs: ")
                        append(failedWarpTiles.joinToString(", ") { "(${it.first},${it.second})" })
                    }
                    if (landmarkContext != null) {
                        append('\n')
                        append(landmarkContext)
                    }
                }
                println("[executor turn=$skillsInvoked] phase=$phase idle=$idleTurns")
                val result = executor.run(phase, executorInput)
                skillsInvoked += 1
                println("[executor result] ${result.lineSequence().take(2).joinToString(" | ").take(160)}")
                val drainedCalls = toolCallLog.drain()
                println("[executor calls] ${drainedCalls.joinToString(" ; ").take(200)}")
                // V5.23: extract UNEXPECTED warp tiles from the toolCallLog (set by
                // WalkOverworldTo.aborted when targeted=false). Drained calls include
                // the abort message verbatim, so this is a stable signal independent
                // of how the LLM phrases its final response.
                drainedCalls.forEach { call ->
                    failedRegex.findAll(call).forEach { m ->
                        val tile = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                        if (failedWarpTiles.add(tile)) {
                            println("[session-memory] +failedWarpTile=$tile (total=${failedWarpTiles.size})")
                            // V5.28: 1x1 block. Was 3x3 in V5.24 on the assumption
                            // FF1 warps could span 2x1, but iter10/iter11 evidence
                            // says each entry tile is a separate trigger. 3x3
                            // overlap sealed a 1-tile pocket at (145,153). Sibling
                            // warps auto-detected on subsequent steps.
                            fog?.let { f ->
                                f.markBlocked(tile.first, tile.second)
                                println("[session-memory] +fog.markBlocked exact $tile")
                            }
                            // V5.25: persist for future sessions. Save immediately
                            // so a crash mid-run doesn't lose the discovery.
                            warpMemory.record(tile.first, tile.second,
                                note = "auto-detected ${java.time.Instant.now()}")
                            warpMemory.save()
                        }
                    }
                }
                trace.record(
                    TraceEvent(
                        turn = 0, role = "executor", phase = phase.toString(),
                        input = executorInput,   // full prompt sent to executor (with current plan + RAM)
                        output = result,         // full executor reasoning + final response, untruncated
                        toolCalls = drainedCalls,
                    )
                )

                val newRam = observer.ramSnapshot()
                idleTurns = if (newRam == lastRam) idleTurns + 1 else 0
                lastRam = newRam
                previousPhase = phase

                onTurnEnd?.invoke(phase, newRam)?.let { hookOutcome ->
                    trace.record(TraceEvent(turn = 0, role = "hook", phase = phase.toString(),
                        note = "onTurnEnd → $hookOutcome"))
                    return hookOutcome
                }

                if (skillsInvoked > budget.maxSkillInvocations) return Outcome.OutOfBudget
                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
                if (elapsedSec > budget.wallClockCapSeconds) return Outcome.OutOfBudget
            }
        } finally {
            trace.close()
        }
    }
}
