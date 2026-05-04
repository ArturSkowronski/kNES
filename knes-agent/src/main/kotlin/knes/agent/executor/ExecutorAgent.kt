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
import knes.agent.perception.VisionInteriorNavigator
import knes.agent.perception.VisionOverworldNavigator
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
    private val visionInteriorNavigator: VisionInteriorNavigator? = null,
    private val visionOverworldNavigator: VisionOverworldNavigator? = null,
    /**
     * Optional override of the canonical "Goal: AtGarlandBattle" paragraph.
     * Use cases: fixture-builder tests targeting an intermediate state (entering
     * a specific town/dungeon), eval runs measuring agent reliability on a
     * narrower task. When null, the production prompt with Garland goal is used.
     */
    private val goalOverride: String? = null,
) {
    private val systemPrompt: String =
        if (goalOverride == null) ff1ExecutorSystemPrompt
        else ff1ExecutorSystemPrompt.replace(GOAL_PARAGRAPH, goalOverride)
    private val skillRegistry = SkillRegistry(toolset, overworldMap, mapSession, fog,
        toolCallLog = toolCallLog,
        visionInteriorNavigator = visionInteriorNavigator,
        visionOverworldNavigator = visionOverworldNavigator)
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
        systemPrompt = systemPrompt,
        maxIterations = 10,   // V5.23.1: dropped from 20→4 to enforce "one skill per turn" but Koog singleRunStrategy chains nodeStart → nodeLLM → nodeExecuteTool → nodeSendToolResult → nodeLLM → nodeFinish (≈6 nodes per tool call). Iter5 evidence: cap=4 fired ITERATION_CAP on every turn. 10 leaves headroom for one tool + retry of LLM response without enabling 6-tool chains.
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
        /** The canonical Garland goal paragraph in [ff1ExecutorSystemPrompt]. Tests
         *  may construct an [ExecutorAgent] with goalOverride to swap this paragraph
         *  for a different goal description without forking the whole prompt.
         */
        val GOAL_PARAGRAPH: String = "- Goal: AtGarlandBattle = Battle.enemyId == 0x7C. Garland is the BOSS of the\n" +
            "  Chaos Shrine (Temple of Fiends), an INTERIOR dungeon — not a scripted bridge\n" +
            "  fight. To reach him: walk north on overworld → enter Chaos Shrine via its\n" +
            "  entry tile (use walkOverworldTo with the shrine's coords as target) →\n" +
            "  exitInterior repeatedly to navigate sub-maps → fight Garland."

        val ff1ExecutorSystemPrompt: String = """
            You are an autonomous Final Fantasy (NES) executor.

            BEHAVIOR: Each time you are invoked, you MUST call exactly one skill (tool).
            After the tool returns its result, briefly state what you did and stop. The
            runtime calls you again with refreshed RAM state for the next decision —
            you do not need to chain tools yourself. Never respond without first invoking
            a tool.

            STUCK DETECTION (V5.21): Before each call, look at your previous tool calls
            in the conversation. If you've made the SAME skill call (same tool + same
            args) 3 times in a row and the phase has NOT changed since the first of
            those calls, you are stuck. Stop repeating it. Call askAdvisor(reason=
            "stuck: <skill> in <phase> for 3 turns") instead. Repeating a skill that
            isn't progressing only burns budget — fresh advice is cheaper.

            UNINTENDED INTERIOR RECOVERY (V5.21): If walkOverworldTo returns ok=false
            with message starting "UNEXPECTED interior entry at world=(X,Y)", that
            world tile has a hidden entry the BFS classifier doesn't model. Recovery:
              1. Call exitInterior repeatedly until phase becomes Overworld.
              2. Then call walkOverworldTo with a target whose path AVOIDS (X,Y) —
                 e.g. detour 2-3 tiles further west or east before going north.
              3. If unsure how to detour, askAdvisor with reason
                 "UNEXPECTED interior at (X,Y) — need detour route".
            Do NOT stay inside the unintended interior trying to "complete" it; the
            agent will burn its full budget walking in circles.

            PROPAGATE FAILURE TO ADVISOR (V5.22): You don't have memory across
            turns — each call you see only the current advisor plan + current RAM.
            So you can't accumulate a "failed tiles" list yourself. INSTEAD, if you
            recently received "UNEXPECTED interior entry at (X,Y)" (visible in your
            current input/observation), and the advisor's current plan still
            recommends a target whose path would cross (X,Y), call
            askAdvisor(reason="avoid (X,Y) — UNEXPECTED warp last turn — propose
            detour"). The advisor MUST react to this hint and shift the waypoint.

            GOAL FOCUS — DEFEAT GARLAND (V5.22): Your terminal goal is the Battle
            phase with enemyId=0x7C (Garland in Chaos Shrine). Random encounters
            on the way are GOOD — call battleFightAll, win XP/gold, and keep going.
            Do NOT waste turns trying to perfectly navigate a town interior you
            entered by accident. After 5 failed exits from the same mapId, just
            call walkUntilEncounter or askAdvisor — anything that breaks the loop.
            Budget burned in Coneria Town is budget not spent fighting Garland.

            Skills available (each is a single tool call):
            - pressStartUntilOverworld: title screen → overworld with default party
            - exitInterior: PRIMARY in Indoors. Decoder-based exit walker — works
              reliably on castles/dungeons, ~13% step success on town overlays
              (handles sub-map transitions automatically). First choice for any
              Indoors phase.
            - walkInteriorVision: ESCALATION only. Vision-driven step-by-step
              walk; use ONLY after exitInterior fails twice on the same map AND
              the advisor explicitly recommends it. Single-frame vision oscillates
              in town overlays — do not call by default.
            - walkOverworldTo(targetX, targetY): walk on overworld using deterministic
              BFS pathfinder; aborts on encounter. Use for traversing terrain to a
              non-town/castle target. Cannot enter towns/castles via the BFS classifier.
            - walkOverworldVision(targetX, targetY): vision-driven walk for cases where
              the BFS classifier refuses an entry tile that is visibly walkable in the
              screenshot. Stops on interior entry, encounter, target reached, or
              visual STUCK. Caveat (V5.21 evidence): does NOT bypass FF1 ROM-encoded
              warp tiles — if a hidden interior entry sits on the path, the engine
              warps regardless of which walker called it. Same UNINTENDED INTERIOR
              RECOVERY rule applies if the result is ok=false.
            - findPath(targetX, targetY): query the overworld pathfinder (does not move)
            - findPathToExit: query the interior pathfinder for the nearest exit
            - battleFightAll: every alive character uses FIGHT until battle ends
            - walkUntilEncounter: walk randomly until a battle starts
            - askAdvisor(reason): consult the planner when stuck or at a phase boundary

            FF1 KNOWLEDGE:
            - Phase will be one of: TitleOrMenu, Overworld(x,y), Indoors(mapId,localX,localY),
              Battle(...), PostBattle.
            - Indoors = inside a building / town / castle (uses local coords).
              walkOverworldTo does NOT work indoors. Default: call exitInterior
              (decoder-based BFS). The skill drives sub-map transitions on its own.
            - V4 hybrid: exitInterior is the primary tool. If it fails twice on
              the same mapId, call askAdvisor(reason="stuck in mapId=N at (lx,ly)")
              — the advisor has access to a screenshot and will give a cardinal
              hint (or recommend walkInteriorVision as last resort).
            - V3.0 evidence: vision-only navigation on town overlays gets ~8%
              step success vs decoder's 13%. Decoder is the better baseline.
              Vision is reserved for cases where the advisor sees the frame and
              concludes the decoder will not progress.
            - The Indoors phase carries `mapId` — useful for advisor consultation
              ("stuck in mapId=8 at lx=5, ly=28").
            - On the overworld: worldX increases EAST; worldY increases SOUTH. North = lower worldY.
            - V2.5: party normally spawns at Overworld(146, 158) right after
              pressStartUntilOverworld. You should NOT see Indoors at the very start.
            - Goal: AtGarlandBattle = Battle.enemyId == 0x7C. Garland is the BOSS of the
              Chaos Shrine (Temple of Fiends), an INTERIOR dungeon — not a scripted bridge
              fight. To reach him: walk north on overworld → enter Chaos Shrine via its
              entry tile (use walkOverworldTo with the shrine's coords as target) →
              exitInterior repeatedly to navigate sub-maps → fight Garland.
            - V2.5.4 hard-impassable: TOWN/CASTLE tiles on the overworld are impassable
              for walkOverworldTo UNLESS they are the explicit target. Even with the
              tile as target the BFS classifier may still refuse entry because tile
              properties are ROM-encoded. For town/castle ENTRY use walkOverworldVision
              instead (V5.18). For overworld traversal toward a non-town target,
              walkOverworldTo is fine.
            - In Battle phase: call battleFightAll. It auto-fights every round AND
              dismisses the PostBattle (XP/rewards) modal automatically.
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
