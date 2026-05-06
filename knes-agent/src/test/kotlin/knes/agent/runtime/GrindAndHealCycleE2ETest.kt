package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import knes.agent.advisor.AdvisorAgent
import knes.agent.executor.ExecutorAgent
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMapLoader
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.perception.RamObserver
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ScreenPng
import knes.agent.tools.results.StateSnapshot
import knes.agent.tools.results.StepResult
import knes.api.EmulatorSession
import java.io.File

/**
 * End-to-end: real ROM + savestate → AgentSession with grindMode active →
 * stubbed advisor that returns a scripted GRIND/GRIND/BRIDGE sequence.
 *
 * Skipped when ROM or savestate is absent (CI without artifacts).
 *
 * Asserts:
 *   - No Outcome.PartyDefeated thrown (session completes without fatal failure).
 *   - Outcome is not null (session ran at all).
 *
 * Stub advisor: overrides consultStrategy() to return scripted tokens (GRIND, GRIND,
 * BRIDGE) without invoking the real Anthropic API. The class constructor still
 * receives AnthropicSession + ModelRouter because AdvisorAgent.plan() might be
 * called after grindMode flips; we pass a dummy key — plan() will fail if called,
 * but the stub executor below prevents that from mattering.
 *
 * Stub executor: overrides run() to return a fixed "noop" string, avoiding any
 * real LLM call. After grindMode flips off, the session invokes the executor; the
 * stub returns quickly so the budget cap (30 skill invocations, 30s wall clock)
 * terminates the session via OutOfBudget without needing a real API key.
 */
class GrindAndHealCycleE2ETest : FunSpec({

    test("grind+rest+bridge cycle with stub advisor") {
        val rom = System.getenv("FF1_ROM") ?: "/Users/askowronski/Priv/kNES/roms/ff.nes"
        val savestate = System.getenv("FF1_SAVESTATE") ?: ""

        if (!File(rom).exists() || savestate.isBlank() || !File(savestate).exists()) {
            println("[GrindAndHealCycleE2ETest] ROM or savestate absent — skipping")
            return@test
        }

        // Real emulator session + ROM + profile.
        val session = EmulatorSession()
        val toolset = EmulatorToolset(session)
        check(toolset.loadRom(rom).ok) { "Failed to load ROM: $rom" }
        check(toolset.applyProfile("ff1").ok) { "Failed to apply profile ff1" }
        check(session.loadState(File(savestate).readBytes())) { "Failed to load savestate: $savestate" }
        // Advance one frame so the NES engine registers the loaded state.
        toolset.step(buttons = emptyList(), frames = 1)

        val romBytes = File(rom).readBytes()
        val overworldMap = OverworldMap.fromRom(romBytes)
        val fog = FogOfWar()
        val mapSession = MapSession(InteriorMapLoader(romBytes), fog)
        val observer = RamObserver(toolset, overworldMap)
        val toolCallLog = ToolCallLog()

        // Dummy AnthropicSession with a fake key — the stubs below never call the
        // real LLM, so no HTTP request is made and the key is never validated.
        val fakeKey = "sk-ant-stub-key-for-e2e-test"
        val router = ModelRouter()
        val anthropic = AnthropicSession(fakeKey)

        // Scripted token sequence: GRIND × 2 → BRIDGE (flips grindModeActive = false).
        val scriptedTokens = listOf("GRIND", "GRIND", "BRIDGE")
        val stubAdvisor = StubAdvisor(anthropic, router, toolset, scriptedTokens)

        // Stub executor: returns a minimal "noop" result without calling the LLM.
        // After grindMode flips off, AgentSession calls executor.run() each turn.
        // The stub short-circuits execution so the budget cap terminates the session.
        val stubExecutor = StubExecutor(anthropic, router, toolset, stubAdvisor,
            overworldMap, mapSession, fog, toolCallLog)

        val budget = Budget(
            maxSkillInvocations = 30,
            maxAdvisorCalls = 20,
            costCapUsd = 0.0,
            wallClockCapSeconds = 30,
        )

        val outcome = AgentSession(
            toolset = toolset,
            observer = observer,
            executor = stubExecutor,
            advisor = stubAdvisor,
            toolCallLog = toolCallLog,
            budget = budget,
            fog = fog,
        ).run()

        println("[GrindAndHealCycleE2ETest] outcome=$outcome advisorTicks=${stubAdvisor.tickCount}")

        // Smoke assertions: session completed without throwing, and party was not wiped.
        outcome shouldNotBe Outcome.PartyDefeated
        outcome shouldNotBe null
    }
})

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

/**
 * Stub advisor: returns scripted tokens from [scripted] list for each
 * consultStrategy() call. Falls back to "BRIDGE" once the list is exhausted.
 * plan() is intentionally NOT overridden — if called it will attempt a real
 * LLM request (which will fail with the fake key). This is acceptable because
 * in grind mode, plan() is only invoked after the budget cap fires or the
 * session transitions out of Overworld; the budget (30s / 30 skills) ensures
 * the session terminates before many plan() calls happen, and the stub executor
 * (below) prevents real advisor plan() calls from being necessary.
 */
private class StubAdvisor(
    anthropic: AnthropicSession,
    modelRouter: ModelRouter,
    toolset: EmulatorToolset,
    private val scripted: List<String>,
) : AdvisorAgent(anthropic, modelRouter, toolset) {
    private var idx = 0
    var tickCount: Int = 0
        private set

    override suspend fun consultStrategy(prompt: String): String {
        tickCount++
        val tok = scripted.getOrNull(idx++) ?: "BRIDGE"
        println("[stub-advisor] tick=$tickCount idx=${idx - 1} → $tok")
        return tok
    }
}

/**
 * Stub executor: overrides run() to return a fixed "noop" result without
 * touching the real Anthropic API. After grindMode flips to false the
 * AgentSession calls executor.run() each turn; this stub returns immediately
 * so the budget / wall-clock cap can fire cleanly.
 */
private class StubExecutor(
    anthropic: AnthropicSession,
    modelRouter: ModelRouter,
    toolset: EmulatorToolset,
    advisor: AdvisorAgent,
    overworldMap: OverworldMap,
    mapSession: MapSession,
    fog: FogOfWar,
    toolCallLog: ToolCallLog,
) : ExecutorAgent(anthropic, modelRouter, toolset, advisor, overworldMap, mapSession, fog, toolCallLog) {
    override suspend fun run(phase: FfPhase, input: String): String {
        println("[stub-executor] phase=$phase → noop")
        return "stub-noop"
    }
}
