package knes.agent

import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.explorer.AnthropicHaikuConsult
import knes.agent.explorer.GeminiVisionConsult
import knes.agent.explorer.HaikuConsult
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.perception.AnthropicVisionInteriorNavigator
import knes.agent.perception.AnthropicVisionOverworldNavigator
import knes.agent.perception.AnthropicVisionPhaseClassifier
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
            val overworldMap = OverworldMap.fromRom(File(rom))
            val fog = FogOfWar()
            val mapSession = MapSession(InteriorMapLoader(File(rom).readBytes()), fog)
            val visionClassifier = AnthropicVisionPhaseClassifier(apiKey = key)
            val visionInteriorNavigator = AnthropicVisionInteriorNavigator(apiKey = key)
            val visionOverworldNavigator = AnthropicVisionOverworldNavigator(apiKey = key)
            val observer = RamObserver(toolset, overworldMap, vision = visionClassifier)
            val toolCallLog = knes.agent.runtime.ToolCallLog()
            val landmarkMemory = LandmarkMemory()
            val advisor = AdvisorAgent(anthropic, router, toolset, viewportSource = overworldMap, interiorSource = mapSession, fog = fog)
            val executor = ExecutorAgent(
                anthropic, router, toolset, advisor, overworldMap, mapSession, fog,
                toolCallLog, visionInteriorNavigator, visionOverworldNavigator,
                landmarks = landmarkMemory,
            )

            // Vision backend for shop classification (Spec 2) and overworld
            // landmark discovery (Spec 3a). Selected via KNES_VISION env var,
            // mirroring ExplorerMain. gemini-pro requires GEMINI_API_KEY; absent
            // falls back to Anthropic Haiku stub which returns NotFound — Spec 2
            // outfit boot phase + Spec 3a bridge phase silently no-op in that case.
            val visionBackend: HaikuConsult = when (System.getenv("KNES_VISION")?.lowercase()) {
                "gemini-pro", "gemini" -> {
                    val gKey = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
                    if (gKey == null) {
                        System.err.println("[main] KNES_VISION=gemini but GEMINI_API_KEY unset — using Anthropic Haiku stub")
                        AnthropicHaikuConsult(apiKey = key)
                    } else {
                        System.err.println("[main] vision backend: Gemini 2.5 Pro")
                        GeminiVisionConsult(apiKey = gKey)
                    }
                }
                else -> {
                    System.err.println("[main] vision backend: Anthropic Haiku (set KNES_VISION=gemini-pro for Gemini)")
                    AnthropicHaikuConsult(apiKey = key)
                }
            }

            AgentSession(
                toolset = toolset,
                observer = observer,
                executor = executor,
                advisor = advisor,
                toolCallLog = toolCallLog,
                budget = Budget(maxSkillInvocations = maxSkills, costCapUsd = costCap, wallClockCapSeconds = wallCap),
                fog = fog,
                landmarkMemory = landmarkMemory,
                outfitVision = visionBackend,
                outfitNavigator = visionInteriorNavigator,
                outfitViewportSource = overworldMap,
                outfitMapSession = mapSession,
                bridgeVision = visionBackend,
                bridgeViewportSource = overworldMap,
            ).run()
        }
    }

    println("OUTCOME: $outcome")
    exitProcess(if (outcome == Outcome.Victory || outcome == Outcome.AtGarlandBattle) 0 else 1)
}
