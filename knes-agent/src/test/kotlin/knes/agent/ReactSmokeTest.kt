package knes.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import knes.agent.tools.EmulatorToolset
import knes.api.EmulatorSession

/**
 * Minimal ToolSet used by the smoke test. Wraps only get_state to avoid
 * Koog 0.5.1 reflection limitation with Map<String,String> parameters.
 */
private class GetStateToolset(private val delegate: EmulatorToolset) : ToolSet {
    @Tool
    @LLMDescription("Get current emulator state: frame count, watched RAM values, CPU registers, and held buttons")
    fun get_state() = delegate.getState()
}

class ReactSmokeTest : FunSpec({

    test("agent calls get_state once and returns") {
        val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        if (key == null) {
            println("ANTHROPIC_API_KEY not set; skipping live test")
            return@test
        }

        val session = EmulatorSession()
        val toolset = GetStateToolset(EmulatorToolset(session))
        val registry = ToolRegistry { tools(toolset) }

        val agent = AIAgent(
            promptExecutor = simpleAnthropicExecutor(key),
            llmModel = AnthropicModels.Sonnet_4_5,
            strategy = reActStrategy(reasoningInterval = 4, name = "smoke"),
            toolRegistry = registry,
            systemPrompt = "You must call the get_state tool exactly once to retrieve the current frame count, then reply DONE.",
        )

        val result = agent.run("Report the current frame count.")
        result shouldNotBe null
    }
})
