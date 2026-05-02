package knes.agent

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FogOfWar
import knes.agent.perception.OverworldMap
import knes.agent.perception.RamObserver
import java.io.File
import knes.agent.runtime.AgentSession
import knes.agent.runtime.Budget
import knes.agent.runtime.Outcome
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val rom = args.firstOrNull { it.startsWith("--rom=") }?.removePrefix("--rom=") ?: "roms/ff.nes"
    val profile = args.firstOrNull { it.startsWith("--profile=") }?.removePrefix("--profile=") ?: "ff1"
    val maxSkills = args.firstOrNull { it.startsWith("--max-skill-invocations=") }?.removePrefix("--max-skill-invocations=")?.toIntOrNull() ?: 80
    val costCap = args.firstOrNull { it.startsWith("--cost-cap-usd=") }?.removePrefix("--cost-cap-usd=")?.toDoubleOrNull() ?: 3.0
    val wallCap = args.firstOrNull { it.startsWith("--wall-clock-cap-seconds=") }?.removePrefix("--wall-clock-cap-seconds=")?.toIntOrNull() ?: 900
    val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("ANTHROPIC_API_KEY not set")

    val outcome: Outcome = runBlocking {
        AnthropicSession(key).use { anthropic ->
            val session = EmulatorSession()
            val toolset = EmulatorToolset(session)
            require(toolset.loadRom(rom).ok) { "Failed to load ROM: $rom" }
            require(toolset.applyProfile(profile).ok) { "Failed to apply profile: $profile" }

            val router = ModelRouter()
            val observer = RamObserver(toolset)
            val advisor = AdvisorAgent(anthropic, router, toolset)
            val overworldMap = OverworldMap.fromRom(File(rom))
            val fog = FogOfWar()
            val executor = ExecutorAgent(anthropic, router, toolset, advisor, overworldMap, fog)

            AgentSession(
                toolset = toolset,
                observer = observer,
                executor = executor,
                advisor = advisor,
                budget = Budget(maxSkillInvocations = maxSkills, costCapUsd = costCap, wallClockCapSeconds = wallCap),
            ).run()
        }
    }

    println("OUTCOME: $outcome")
    exitProcess(if (outcome == Outcome.Victory || outcome == Outcome.AtGarlandBattle) 0 else 1)
}
