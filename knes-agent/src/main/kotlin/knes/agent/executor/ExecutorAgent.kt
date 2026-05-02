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
import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportSource
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val advisor: AdvisorAgent,
    private val viewportSource: ViewportSource,
    private val fog: FogOfWar,
) {
    private val skillRegistry = SkillRegistry(toolset, viewportSource, fog)
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
        maxIterations = 20,   // Koog counts node executions, not LLM calls. V2.3 adds findPath; the model may chain findPath → walkOverworldTo (2 tool calls = ~6-8 iterations) plus final response. 20 leaves slack without runaway.
    )

    suspend fun run(phase: FfPhase, input: String): String = try {
        newAgent(phase).run(input)
    } catch (e: Exception) {
        // singleRunStrategy + maxIterations=2 should rarely cap, but if the model keeps
        // calling tools the cap will fire. Treat as a normal turn outcome; outer loop
        // observes RAM and decides next steps.
        if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") {
            "ITERATION_CAP: ${e.message?.take(120)?.trim()}"
        } else throw e
    }

    companion object {
        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor.

            BEHAVIOR: Each time you are invoked, you MUST call exactly one skill (tool).
            After the tool returns its result, briefly state what you did and stop. The
            runtime calls you again with refreshed RAM state for the next decision —
            you do not need to chain tools yourself. Never respond without first invoking
            a tool.

            Skills available (each is a single tool call):
            - pressStartUntilOverworld: title screen → overworld with default party
            - exitBuilding: walk south out of a town/castle interior (use when Indoors)
            - walkOverworldTo(targetX, targetY): greedy walk on overworld; aborts on encounter
            - battleFightAll: every alive character uses FIGHT until battle ends
            - walkUntilEncounter: walk randomly until a battle starts
            - askAdvisor(reason): consult the planner when stuck or at a phase boundary

            FF1 KNOWLEDGE:
            - Phase will be one of: TitleOrMenu, Overworld(x,y), Indoors(localX,localY),
              Battle(...), PostBattle.
            - Indoors = inside a building (uses local coords). walkOverworldTo does NOT
              work indoors. Call exitBuilding first to reach the world map.
            - **In V2, after pressStartUntilOverworld the party often starts Indoors
              (inside Coneria castle). FIRST call exitBuilding** before trying to navigate.
            - On the overworld: worldX increases EAST; worldY increases SOUTH. North = lower worldY.
            - Goal: Garland is a SCRIPTED encounter on the bridge NORTH of Coneria. After
              exiting the castle, walk north (decreasing worldY) until Battle(Garland).
            - In Battle phase, call battleFightAll. After PostBattle, resume walking north.
        """.trimIndent()
    }
}
