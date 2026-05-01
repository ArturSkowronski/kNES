package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.FfPhase
import knes.agent.perception.RamObserver
import knes.agent.perception.ScreenshotPolicy
import knes.agent.tools.EmulatorToolset
import java.nio.file.Path

data class Budget(val maxToolCalls: Int = 2000, val maxAdvisorCalls: Int = 30)

class AgentSession(
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val executor: ExecutorAgent,
    private val advisor: AdvisorAgent,
    private val budget: Budget = Budget(),
    runDir: Path = Trace.newRunDir(),
) {
    private val trace = Trace(runDir)
    private val screenshotPolicy = ScreenshotPolicy()

    /**
     * Drives the agent until success/failure. Each "outer turn":
     *   1. Observe RAM, classify phase.
     *   2. Check SuccessCriteria — terminate if Victory / PartyDefeated.
     *   3. If phase changed since last turn, ask advisor for a plan.
     *   4. Run the executor for up to one phase (its internal reActStrategy iterates;
     *      we re-enter when phase changes or executor returns).
     *   5. Watchdog: bump idleTurns if RAM didn't change; on threshold, force advisor.
     *
     * Termination: SuccessCriteria != InProgress, or budget exhausted.
     */
    suspend fun run(): Outcome {
        var previousPhase: FfPhase? = null
        var currentPlan = "Start the game from the title screen and begin a new game."
        var idleTurns = 0
        var lastRam: Map<String, Int> = emptyMap()
        var advisorCalls = 0
        var toolCalls = 0   // approximate; one bump per executor outer-turn

        try {
            while (true) {
                val phase = observer.observe()
                val ram = observer.ramSnapshot()

                val outcome = SuccessCriteria.evaluate(phase)
                if (outcome != Outcome.InProgress) {
                    trace.record(TraceEvent(0, "outcome", phase.toString(), note = outcome.name))
                    return outcome
                }

                val phaseChanged = previousPhase == null || previousPhase!!::class != phase::class
                if (phaseChanged || idleTurns >= 20) {
                    if (++advisorCalls > budget.maxAdvisorCalls) return Outcome.OutOfBudget
                    val attachShot = screenshotPolicy.shouldAttach(previousPhase, phase)
                    val obs = buildString {
                        append("Phase: $phase\nRAM: $ram\n")
                        if (attachShot) {
                            // Mention only that a screenshot was taken; full base64 in trace, not in prompt.
                            // The advisor's ReadOnlyToolset has getScreen() if it wants raw pixels.
                            append("(screenshot available via get_screen)\n")
                        }
                        append("Reason: ${if (phaseChanged) "phase change" else "watchdog stuck"}")
                    }
                    println("[advisor #$advisorCalls] phase=$phase reason=${if (phaseChanged) "phase change" else "watchdog stuck"}")
                    currentPlan = advisor.plan(obs)
                    println("[advisor plan] ${currentPlan.lineSequence().take(3).joinToString(" | ").take(200)}")
                    trace.record(TraceEvent(0, "advisor", phase.toString(), note = currentPlan.take(500)))
                    idleTurns = 0
                }

                val executorInput = "Plan:\n$currentPlan\n\nCurrent phase: $phase\nRAM: $ram"
                println("[executor turn=$toolCalls] phase=$phase idle=$idleTurns")
                val result = executor.run(executorInput)
                toolCalls += 1
                println("[executor result] ${result.lineSequence().take(2).joinToString(" | ").take(160)}")
                trace.record(TraceEvent(0, "executor", phase.toString(), note = result.take(500)))

                val newRam = observer.ramSnapshot()
                idleTurns = if (newRam == lastRam) idleTurns + 1 else 0
                lastRam = newRam
                previousPhase = phase

                if (toolCalls > budget.maxToolCalls) return Outcome.OutOfBudget
            }
        } finally {
            trace.close()
        }
    }
}
