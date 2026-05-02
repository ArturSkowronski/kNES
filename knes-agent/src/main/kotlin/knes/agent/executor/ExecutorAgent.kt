package knes.agent.executor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import knes.agent.advisor.AdvisorAgent
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.FfPhase
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val advisor: AdvisorAgent,
) {
    private val skillRegistry = SkillRegistry(toolset)
    private val advisorTool = AdvisorToolset(advisor)
    private val registry = ToolRegistry {
        tools(skillRegistry)
        tools(advisorTool)
    }

    private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
        promptExecutor = anthropic.executor,
        llmModel = modelRouter.modelFor(phase, AgentRole.EXECUTOR),
        toolRegistry = registry,
        strategy = singleRunStrategy(),
        systemPrompt = ff1ExecutorSystemPrompt,
    )

    suspend fun run(phase: FfPhase, input: String): String = newAgent(phase).run(input)

    companion object {
        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor. Drive the game toward
            the start of the Garland battle by invoking exactly one scripted skill per turn
            (or asking the advisor when stuck).

            Skills available this turn (each is a single tool call):
            - pressStartUntilOverworld(maxAttempts) — title screen → overworld with party
            - walkOverworldTo(targetX, targetY, maxSteps) — greedy walk; aborts on encounter
            - battleFightAll() — every alive character uses FIGHT until battle ends
            - walkUntilEncounter() — walk randomly until a battle starts
            - getState() — read RAM and frame count
            - askAdvisor(reason) — consult the planner when stuck or at a phase boundary

            Conventions:
            - Pick exactly one tool per turn. Do not narrate state — just choose a skill.
            - The outer loop will observe RAM after your skill returns and call you again.
            - When uncertain (unfamiliar phase, last skill failed, stuck), call askAdvisor.
            - Do NOT call getState repeatedly to "look around"; call a skill that advances state.
        """.trimIndent()
    }
}
