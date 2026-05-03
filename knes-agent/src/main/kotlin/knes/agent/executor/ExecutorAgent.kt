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
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.runtime.ToolCallLog
import knes.agent.skills.SkillRegistry
import knes.agent.tools.EmulatorToolset

class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val advisor: AdvisorAgent,
    private val overworldMap: OverworldMap,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
    private val toolCallLog: ToolCallLog = ToolCallLog(),
) {
    private val skillRegistry = SkillRegistry(toolset, overworldMap, mapSession, fog,
        toolCallLog = toolCallLog)
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
            - exitInterior: walk to nearest exit (DOOR/STAIRS/WARP or south-edge) of the
              current FF1 interior map using a deterministic BFS pathfinder. Handles
              sub-map transitions automatically. Use when phase is Indoors.
            - walkOverworldTo(targetX, targetY): walk on overworld using deterministic
              BFS pathfinder; aborts on encounter
            - findPath(targetX, targetY): query the overworld pathfinder (does not move)
            - findPathToExit: query the interior pathfinder for the nearest exit
            - battleFightAll: every alive character uses FIGHT until battle ends
            - walkUntilEncounter: walk randomly until a battle starts
            - askAdvisor(reason): consult the planner when stuck or at a phase boundary

            FF1 KNOWLEDGE:
            - Phase will be one of: TitleOrMenu, Overworld(x,y), Indoors(mapId,localX,localY),
              Battle(...), PostBattle.
            - Indoors = inside a building / town / castle (uses local coords). walkOverworldTo
              does NOT work indoors. Call exitInterior to reach the world map.
            - V2.4: Indoors phase carries `mapId` identifying the interior in ROM.
              The exitInterior skill knows how to reload the map when you transition
              to a sub-map (stairs/warp). Just keep calling exitInterior until phase
              becomes Overworld.
            - V2.4: when phase is Indoors you also receive an ASCII MAP showing the
              current interior. Glyphs: @=party, .=floor, ^=wall, ~=water, D=door,
              >=stairs, *=warp, ?=unseen/outside, X=blocked-confirmed.
            - On the overworld: worldX increases EAST; worldY increases SOUTH. North = lower worldY.
            - Goal: Garland is a SCRIPTED encounter on the bridge NORTH of Coneria. After
              exiting any interior, walk north (decreasing worldY) until Battle(Garland).
            - In Battle phase: call battleFightAll. It auto-fights every round.
            - In PostBattle phase: call battleFightAll AGAIN — it dismisses the
              post-battle (rewards/XP) modal by tapping A. You CANNOT walk while
              PostBattle is on screen; walkOverworldTo will return BLOCKED because
              the engine ignores movement input during the modal. Only after
              battleFightAll clears PostBattle will phase become Overworld and walking
              resume. Do NOT call walkOverworldTo while phase is PostBattle.
            - After PostBattle clears (phase = Overworld), resume walking north.
        """.trimIndent()
    }
}
