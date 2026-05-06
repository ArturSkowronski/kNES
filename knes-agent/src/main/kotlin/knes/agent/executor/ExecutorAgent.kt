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

open class ExecutorAgent(
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
        maxIterations = 24,   // V5.23.2: 10 still hit ITERATION_CAP (iter6). Even single-tool calls in Koog 0.6.1 singleRunStrategy can take 8-10 nodes when tool result triggers extra LLM reasoning. 2026-05-06 Garland attempt: 16 hit ITERATION_CAP repeatedly when executor chained walkOverworldTo retries + battleFightAll + askAdvisor in same turn. Bumped to 24 — small step above 20-cap risk but within range that V2-V5 occasionally tolerated. Tracking ITERATION_CAP rate across iterations.
    )

    open suspend fun run(phase: FfPhase, input: String): String = try {
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

            GOAL FOCUS — DEFEAT GARLAND (V5.22+V5.26): Your terminal goal is
            the Battle phase with enemyId=0x7C (Garland in Chaos Shrine). Random
            encounters on the way are GOOD — call battleFightAll, win XP/gold,
            keep going. Do NOT waste turns trying to perfectly navigate a town
            interior you entered by accident. After 5 failed exits from the
            same mapId, call askAdvisor with "stuck in mapId=N for 5+ exits".
            Budget burned in Coneria Town is budget not spent fighting Garland.

            Skills available (V5.26 — INTENT-LEVEL, deterministic only). Each
            tool is a self-contained perception-action loop. You issue an
            INTENT; the runtime walks the steps, checks RAM, returns success/
            failure. You do NOT pick directions — that is the runtime's job.

            - pressStartUntilOverworld: title screen → overworld with default party
            - walkOverworldTo(targetX, targetY): deterministic BFS walk on the
              overworld toward (X, Y). Aborts on encounter. Honors FogOfWar
              blocks (failed warp tiles auto-blocked). Do NOT call this when
              already Indoors.
            - exitInterior: deterministic BFS walk to the nearest interior exit
              (DOOR / STAIRS / WARP / south-edge). Drives sub-map transitions on
              its own. FIRST CHOICE for castles/dungeons. ~13% step success on
              town overlays — if it fails twice on the same mapId, switch to
              exploreInteriorFrontier.
            - exploreInteriorFrontier (V5.29): deterministic frontier explorer.
              Walks the party toward the nearest UNVISITED reachable tile in
              the current interior, persisting visited tiles across runs in
              InteriorMemory. Use when exitInterior fails on town overlays —
              full map coverage exposes exits as side effects. Stops on
              phase=Overworld, encounter, fully-explored map, or repeated
              blocked direction.
            - findPath(targetX, targetY): READ-ONLY query of the overworld BFS.
              Returns path length + first directions, or BLOCKED. Cheap; call
              before walkOverworldTo to verify reachability if uncertain.
            - findPathToExit: READ-ONLY query of the interior BFS for the
              current mapId.
            - battleFightAll: scripted FIGHT loop until Battle ends; also
              dismisses PostBattle modal automatically.
            - askAdvisor(reason): consult the strategic planner. Use when
              stuck, at a phase boundary, or when no listed skill maps to the
              current goal.

            FF1 KNOWLEDGE:
            - Phase will be one of: TitleOrMenu, Overworld(x,y), Indoors(mapId,localX,localY),
              Battle(...), PostBattle.
            - Indoors = inside a building / town / castle (uses local coords).
              walkOverworldTo does NOT work indoors. Default: call exitInterior
              (decoder-based BFS). The skill drives sub-map transitions on its own.
            - exitInterior is the only Indoors movement tool you have. If it
              fails twice on the same mapId, call askAdvisor(reason="stuck in
              mapId=N at (lx,ly)") — the advisor has access to a screenshot
              and will inspect the layout for an alternative.
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
            - V2.5.4 hard-impassable: TOWN/CASTLE tiles on the overworld are
              impassable for walkOverworldTo UNLESS they are the explicit
              target. Even with the tile as target the BFS may refuse because
              tile properties are ROM-encoded.

            T/C ENTRY IS LEGITIMATE PROGRESSION (V5.27): the BFS rule above
            often makes the world look "walled in" — at e.g. (152, 151) the
            agent reports "trapped, surrounded by town/castle tiles". This is
            wrong framing. In FF1, walking INTO a town or castle is normal
            traversal, not a failure mode. If walkOverworldTo to a far target
            returns BLOCKED and your ASCII WORLD VIEW shows T or C glyphs in
            cardinal neighbours, pick one of those T/C tiles as the explicit
            target. The agent will enter that interior; once inside, call
            exploreInteriorFrontier to traverse the town/castle and emerge
            on the OTHER side via the BFS-discovered exit.

            DELIBERATE WARP ENTRY (V5.30): the same logic applies to the
            "Session memory — known FF1 warp tiles" entries. Those tiles
            look like grass on the ASCII map (the classifier doesn't see
            them) but are actually town/castle entries the runtime learned
            about by tripping. They are FOG-BLOCKED to prevent accidental
            transit, but if walkOverworldTo to a far target returns BLOCKED
            and a warp tile is the closest forward step, you may target
            that warp tile EXPLICITLY: walkOverworldTo(warpX, warpY). The
            BFS allows the destination tile through fog (V5.30 rule). The
            walk will report ok=true with "reached target interior" — then
            call exploreInteriorFrontier to traverse and emerge.
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
