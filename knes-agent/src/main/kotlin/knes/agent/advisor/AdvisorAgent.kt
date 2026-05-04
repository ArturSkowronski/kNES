package knes.agent.advisor

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import knes.agent.llm.AgentRole
import knes.agent.llm.AnthropicSession
import knes.agent.llm.ModelRouter
import knes.agent.perception.AsciiMapRenderer
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset

/**
 * Single-shot planner. Each plan() call does ONE Koog AIAgent.run (singleRunStrategy):
 * the LLM either returns plain text or invokes one read-only tool (getState/getScreen).
 * Read-only access — advisor must never mutate emulator state.
 *
 * V2.3: when called with an Overworld phase and a configured ViewportSource + FogOfWar,
 * the user-facing observation is augmented with an ASCII map (terrain + fog stats +
 * blocked tiles) — Gemini-PP finding: textual tile grids match raw screenshots for
 * spatial reasoning at much lower cost.
 *
 * V2.4: extended to also render ASCII map for Indoors phase when interiorSource is provided.
 */
class AdvisorAgent(
    private val anthropic: AnthropicSession,
    private val modelRouter: ModelRouter,
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource? = null,  // overworld source, kept for backward-compat
    private val interiorSource: ViewportSource? = null,
    private val fog: FogOfWar? = null,
    /** Mirror of ExecutorAgent.goalOverride — see that field's docstring. */
    private val goalOverride: String? = null,
) {
    private val effectiveSystemPrompt: String =
        if (goalOverride == null) systemPrompt
        else systemPrompt.replace(GOAL_PARAGRAPH, goalOverride)
    private val readOnlyTools = ReadOnlyToolset(toolset)
    private val registry = ToolRegistry { tools(readOnlyTools) }

    private fun newAgent(phase: FfPhase): AIAgent<String, String> = AIAgent(
        promptExecutor = anthropic.executor,
        llmModel = modelRouter.modelFor(phase, AgentRole.ADVISOR),
        toolRegistry = registry,
        strategy = singleRunStrategy(),
        systemPrompt = effectiveSystemPrompt,
        maxIterations = 8,   // Koog counts node executions; advisor may inspect state once + produce plan
    )

    suspend fun plan(phase: FfPhase, observation: String): String {
        val augmented = augmentMapView(phase, observation)
        return try {
            newAgent(phase).run(augmented)
        } catch (e: Exception) {
            if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") {
                "ADVISOR_ITERATION_CAP: stay the course with previous plan"
            } else throw e
        }
    }

    private fun augmentMapView(phase: FfPhase, observation: String): String {
        val (src, partyXY) = when (phase) {
            is FfPhase.Overworld -> viewportSource to (phase.x to phase.y)
            is FfPhase.Indoors -> {
                if (phase.mapId >= 0 && interiorSource is MapSession) {
                    interiorSource.ensureCurrent(phase.mapId)
                }
                interiorSource to (phase.localX to phase.localY)
            }
            else -> null to null
        }
        if (src == null || partyXY == null) return observation
        val f = fog ?: return observation
        val viewport = src.readViewport(partyXY)
        f.merge(viewport)
        val mapBlock = AsciiMapRenderer.render(viewport, f)
        return buildString {
            append(observation)
            if (!observation.endsWith('\n')) append('\n')
            append('\n')
            append(mapBlock)
        }
    }

    companion object {
        /** Goal paragraph in [systemPrompt] — see ExecutorAgent.GOAL_PARAGRAPH for the
         *  pattern. Construct an [AdvisorAgent] with a non-null goalOverride to swap. */
        val GOAL_PARAGRAPH: String = "  - Goal: AtGarlandBattle = Battle.enemyId == 0x7C. Garland is the BOSS of the\n" +
            "    Chaos Shrine (Temple of Fiends), an interior dungeon. He is NOT a scripted\n" +
            "    bridge encounter. To reach him you must (a) walk north on the overworld\n" +
            "    from spawn to the Chaos Shrine entrance, (b) enter the shrine, (c) navigate\n" +
            "    its dungeon, (d) defeat the shrine miniboss room."

        val systemPrompt: String = """
            You are the planner for an autonomous Final Fantasy (NES) agent.
            Given the current emulator state, output a short numbered plan (1–6 steps) the
            executor will follow until the next phase change. Each step must be actionable
            using the available kNES skills.

            GROUND TRUTH ONLY (V5.21): your ONLY trustworthy sources are
              (1) the RAM dump,
              (2) the ASCII WORLD VIEW / interior map block appended to your input,
              (3) the screenshot if you call getScreen.
            FF1 coordinates from your training data are UNRELIABLE — the in-game world is
            byte-encoded in ROM and may not match any wiki coordinates. NEVER cite a
            specific entry-tile coordinate ("Coneria Castle is at (152, 159)") unless
            you can SEE the C/T glyph at that exact tile in the ASCII map. If you don't
            see a T or C glyph in the viewport, the right plan is "walk N/S/E/W to
            expand the viewport, then re-evaluate" — NOT a guess at known FF1 geography.

            UNEXPECTED INTERIOR — DETOUR (V5.21+V5.22): if the executor's recent
            askAdvisor reason cites "avoid (X,Y) — UNEXPECTED warp" or RAM shows
            the party warped into an interior at a tile that wasn't the planned
            target, that tile is a hidden FF1 ROM entry the BFS classifier doesn't
            model. Plan: (a) exitInterior until phase=Overworld, (b) propose a
            fresh walkOverworldTo target whose path is FAR off-axis from (X,Y) —
            shift the waypoint by at least 5 tiles in X or Y from the failure tile,
            so BFS cannot route the party back through it. Do NOT re-suggest a
            target that would route through (X,Y). The executor has no cross-turn
            memory; once the warp is no longer in its current input, it WILL repeat
            the mistake unless your plan explicitly steers it elsewhere.

            GOAL FOCUS (V5.22): the terminal objective is Battle.enemyId=0x7C
            (Garland). Random encounters give XP and gold — they're progress,
            not setbacks. If executor is stuck in a town interior loop, prioritise
            "break the loop" over "exit cleanly": suggest walkUntilEncounter,
            walkInteriorVision in a never-tried direction, or even
            pressStartUntilOverworld as a last-ditch reset (if title screen is
            reachable). Budget spent escaping Coneria is budget lost to Garland.
              - pressStartUntilOverworld: title screen → overworld with default party
              - exitInterior: PRIMARY in Indoors. Decoder-based BFS exit walker.
                Reliable on castles/dungeons, ~13% step success on town overlays.
                Suggest this first for any Indoors phase.
              - walkInteriorVision: ESCALATION. Single-frame vision navigator. Only
                suggest after exitInterior failed twice on the same mapId AND the
                screenshot reveals a clearly walkable direction the decoder missed.
              - walkOverworldTo(x, y): deterministic BFS walk to coords on the OVERWORLD;
                aborts on encounter. Use for traversing terrain to a non-town/castle target.
              - walkOverworldVision(x, y): PREFERRED for entering a town or castle on the
                overworld (V5.18+). Vision-driven step-by-step walk that bypasses the BFS
                classifier's hard-impassable rule for entry tiles.
              - walkUntilEncounter: walk randomly until a battle starts
              - battleFightAll: every alive character uses FIGHT until battle ends

            FF1 KNOWLEDGE — use this to plan, not your training-data memory of the game:
              - Phases you may see: TitleOrMenu, Overworld(x, y),
                Indoors(mapId, localX, localY), Battle(...), PostBattle, PartyDefeated.
              - Indoors = inside a town/castle/dungeon; uses LOCAL coords. walkOverworldTo
                will not work indoors. Call exitInterior to reach the world map. The skill
                drives sub-map transitions (stairs/warps) on its own; keep calling it until
                the phase becomes Overworld.
              - Coord system on the overworld: worldX increases EAST; worldY increases SOUTH.
                Lower worldY = north, higher worldY = south.
              - V2.3+: when on the Overworld you receive an ASCII WORLD VIEW (16x16 around
                party). Glyphs: @=party, .=grass, ^=mountain (impassable), ~=water (impassable),
                F=forest, R=road, B=bridge, T=town, C=castle, ?=unseen, X=blocked-confirmed.
                Use this map to plan waypoints — DO NOT trust your training-data memory of
                FF1 geography. The executor has a deterministic findPath(x,y) tool that BFS-
                searches this same viewport; suggest waypoints reachable per the map.
              - V4 hybrid: interior navigation defaults to the DECODER (exitInterior).
                When called with reason "stuck in interior" you have a screenshot
                available via getScreen — INSPECT IT and give the executor a single
                clear cardinal hint: "walk SOUTH from here", "try EAST 3 tiles",
                etc. Phrase as a numbered plan step the executor will follow with
                walkUntilEncounter or by tapping cardinal buttons via the existing
                walk skills. Only after two such hints fail should you escalate to
                walkInteriorVision.
              - For the overworld you may continue to propose (worldX, worldY)
                waypoints; that pathfinder is solid.
              - You may also receive an ASCII map of the interior; cross-reference
                it with the screenshot — they should agree on walls but the
                screenshot is ground truth for NPCs and dynamic obstacles.
              - V2.5: after pressStartUntilOverworld the party normally appears on the
                overworld at world (146, 158) — that is INSIDE the Coneria peninsula but
                NOT in any interior map (locationType=0, localX=0, localY=0). RAM-override
                in the phase classifier recognises this as Overworld(146,158) directly;
                you should NOT see Indoors here.
              - Real Indoors states arise after the party walks INTO an entrance tile.
                Find candidate entry tiles by reading T (town) or C (castle) glyphs in
                the ASCII WORLD VIEW. Do NOT cite specific coordinates from training
                data — the only valid entry tile is one you can SEE in the current
                viewport. If no T/C is visible, expand the viewport by walking before
                proposing an entry plan.
              - Goal: AtGarlandBattle = Battle.enemyId == 0x7C. Garland is the BOSS of the
                Chaos Shrine (Temple of Fiends), an interior dungeon. He is NOT a scripted
                bridge encounter. To reach him you must (a) walk north on the overworld
                from spawn to the Chaos Shrine entrance, (b) enter the shrine, (c) navigate
                its dungeon, (d) defeat the shrine miniboss room.
              - V2.5.4 hard-impassable rule: TOWN and CASTLE tiles are IMPASSABLE for
                walkOverworldTo when they are NOT the destination. Even with the tile as
                target the BFS classifier may still refuse entry because tile properties
                are ROM-encoded. For TOWN/CASTLE entry suggest walkOverworldVision(x, y)
                — e.g. walkOverworldVision(targetX=152, targetY=159) for Coneria Castle.
                Reserve walkOverworldTo for dungeons (Chaos Shrine entry tile) and pure
                terrain traversal.
              - The 256x256 overworld pathfinder is solid for terrain — trust its
                output for non-town/castle waypoints. If findPath returns BLOCKED for a
                target, that target may be unreachable (isolated pocket) — pick a
                different waypoint.
              - DO NOT propose specific routes from training data ("go WEST to x=140
                first" etc.). Iter1+iter2 evidence: the model's confidently-asserted
                "grass corridor at x=140" route led the party straight into a hidden
                interior entry at (145, 152) twice in a row. Instead: pick a waypoint
                ~10 tiles toward the goal direction visible in the ASCII map, let
                walkOverworldTo navigate, observe RAM, re-plan from new viewport.
              - Random encounters along the way are normal — handle them with battleFightAll,
                then resume walking. battleFightAll also dismisses the PostBattle modal.

            Output: a numbered plan with concrete coords. Do NOT execute the plan;
            only describe it.
        """.trimIndent()
    }
}
