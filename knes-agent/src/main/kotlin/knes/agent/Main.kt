package knes.agent

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.perception.RamObserver
import knes.agent.runtime.AgentSession
import knes.agent.runtime.Budget
import knes.agent.runtime.Outcome
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val outcome: Outcome = runBlocking {
        val rom = args.firstOrNull { it.startsWith("--rom=") }?.removePrefix("--rom=") ?: "roms/ff1.nes"
        val profile = args.firstOrNull { it.startsWith("--profile=") }?.removePrefix("--profile=") ?: "ff1"
        val maxSteps = args.firstOrNull { it.startsWith("--max-steps=") }?.removePrefix("--max-steps=")?.toIntOrNull() ?: 2000
        val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
            ?: error("ANTHROPIC_API_KEY not set")

        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        require(toolset.loadRom(rom).ok) { "Failed to load ROM: $rom" }
        require(toolset.applyProfile(profile).ok) { "Failed to apply profile: $profile" }

        val observer = RamObserver(toolset)
        val advisor = AdvisorAgent(key, toolset)
        val executor = ExecutorAgent(key, toolset, advisor)

        AgentSession(
            toolset = toolset,
            observer = observer,
            executor = executor,
            advisor = advisor,
            budget = Budget(maxToolCalls = maxSteps),
        ).run()
    }

    println("OUTCOME: $outcome")
    exitProcess(if (outcome == Outcome.Victory) 0 else 1)
}
