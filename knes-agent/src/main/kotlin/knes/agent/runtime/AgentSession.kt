package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.FfPhase
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
    runDir: Path = Trace.newRunDir(),
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

                val executorInput = "Plan:\n$currentPlan\n\nCurrent phase: $phase\nRAM: $ram"
                println("[executor turn=$skillsInvoked] phase=$phase idle=$idleTurns")
                val result = executor.run(phase, executorInput)
                skillsInvoked += 1
                println("[executor result] ${result.lineSequence().take(2).joinToString(" | ").take(160)}")
                val drainedCalls = toolCallLog.drain()
                println("[executor calls] ${drainedCalls.joinToString(" ; ").take(200)}")
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

                if (skillsInvoked > budget.maxSkillInvocations) return Outcome.OutOfBudget
                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
                if (elapsedSec > budget.wallClockCapSeconds) return Outcome.OutOfBudget
            }
        } finally {
            trace.close()
        }
    }
}
