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
open class AdvisorAgent(
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

    /**
     * Strategy-mode advisor consult. Different system prompt
     * (StrategyAdvice.SYSTEM_PROMPT, no Koog tools, single-token output).
     * See spec §3.3.
     */
    open suspend fun consultStrategy(prompt: String): String {
        val agent = AIAgent(
            promptExecutor = anthropic.executor,
            llmModel = modelRouter.modelFor(FfPhase.Overworld(0, 0), AgentRole.ADVISOR),
            toolRegistry = ToolRegistry { },
            strategy = singleRunStrategy(),
            systemPrompt = StrategyAdvice.SYSTEM_PROMPT,
            maxIterations = 2,
        )
        return try {
            agent.run(prompt)
        } catch (e: Exception) {
            if (e::class.simpleName == "AIAgentMaxNumberOfIterationsReachedException") "GRIND"
            else throw e
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

            GOAL FOCUS (V5.22+V5.26): the terminal objective is Battle.enemyId
            =0x7C (Garland). Random encounters give XP and gold — they're
            progress, not setbacks. If executor is stuck in a town interior
            loop, suggest exitInterior repeatedly with maxSteps bumped (e.g.
            128), or recommend a different sub-map target if the screenshot
            shows one. Budget spent escaping Coneria is budget lost to
            Garland — at some point declare the run unsalvageable and accept it.

            FRONTIER EXPLORATION BIAS (V5.32): when walkOverworldTo fails
            twice in a row on the same target with reasons like
            "target tile is impassable", "no path within viewport", or
            "did not reach in N steps", STOP retrying that target. The
            picked coordinate is likely WATER/MOUNTAIN or unreachable from
            current position. Instead:
              (a) Pick a waypoint at the EDGE of the visible 16×16
                  viewport in the rough direction of the goal. Edge tiles
                  expand the fog and reveal new structure even when the
                  long-range goal can't be reached directly.
              (b) PREFER fog frontiers — '?' tiles in the ASCII map —
                  over re-trying known-walkable targets. Unexplored
                  tiles often hide the path forward; "weird" isolated
                  glyphs in the viewport (single C/T amid GRASS) are
                  often dungeon entries worth probing.
              (c) Before committing to a long-range walkOverworldTo,
                  consult findPath with that target. If findPath returns
                  no path, pick a different waypoint within ±3 tiles —
                  prefer GRASS/FOREST tiles you can SEE in the ASCII map
                  rather than blind training-data coords. NEVER pick a
                  target without verifying it's a passable terrain glyph
                  in the current viewport or at minimum near a passable
                  glyph.
              (d) Map boundaries are valid exploration targets in their
                  own right. If the ASCII map shows water/forest/mountain
                  walls, target a tile that REACHES the wall (forces
                  viewport shift) rather than picking a coord that's
                  already unreachable. Iterate: walk to edge, observe
                  new viewport, re-plan.

            XP / LEVEL GRIND STRATEGY (V5.34.5): the default party at level 0
            is FRAGILE — single battle takes ~10 rounds, char1 HP can drop to
            10/35 after one encounter. attempt11 evidence: walking from spawn
            toward bridge (157,141) triggers ~23 random encounters in
            high-encounter Coneria peninsula terrain; multi-screen postbattle
            (dmg / XP / level / gold) per fight × 23 = ~150 dismiss cycles =
            full budget spent before reaching bridge. Therefore:
              (a) After 5+ battles in a single overworld walk, expect HP to
                  be low. Plan a Coneria castle/town visit ONLY for
                  buying potions OR resting at inn (heal 1 gp); do NOT detour
                  to inn unless char_hp < 30% maxHp.
              (b) Random encounter chains are FEATURE not bug — XP from
                  overworld walks reduces battle length quadratically (lvl 1
                  party = 10-rd battles; lvl 3 party = 3-rd battles). Don't
                  panic-restart a run after few fights; ride them out.
              (c) If a single walkOverworldTo skill call triggers 5+ encounters,
                  the postbattle dismiss budget will exhaust before reaching
                  target. Break long walks into intermediate waypoints
                  (every 8-10 tiles) so per-skill encounter count stays low.
                  After waypoint reached, fresh advisor call → fresh budget.
              (d) Level-up screens add 3-5 extra postbattle dismiss cycles per
                  character that levels. Plan budget accordingly: 4-char
                  level-up = ~20 extra dismiss cycles on top of standard
                  battle resolution.

            CONERIA TOWN OVERLAY NAVIGATION (V5.34.4): when phase becomes
            `Indoors(mapId=0, isTown=true)`, the party has entered the
            Coneria town overlay layer (not a real interior, not a trap).
            DO NOT cycle exitInterior repeatedly — V5.29 evidence shows
            ~13% step success per call on town overlays. After 1 failed
            exitInterior, switch strategy:
              (a) exploreInteriorFrontier with maxSteps=64 — covers town
                  tiles tile-by-tile, naturally exposing exits.
              (b) If that also fails, walkOverworldTo to a coord OUTSIDE
                  the Coneria overlay zone (~6+ tiles away from castle),
                  e.g. (140,160) west or (157,141) bridge, to escape the
                  overlay re-entry trap.
              (c) NEVER call exitInterior more than 2 times in a row in
                  town overlay — it burns budget without exiting.
            Skill repertoire (V5.26 — INTENT-LEVEL only, deterministic):
              - pressStartUntilOverworld: title screen → overworld with default party
              - exitInterior: deterministic BFS to nearest interior exit. FIRST
                CHOICE for castles/dungeons. ~13% step success on town overlays.
              - exploreInteriorFrontier (V5.29): deterministic frontier explorer.
                Walks toward the nearest UNVISITED reachable tile, persisting
                visited tiles in InteriorMemory. Use when exitInterior fails
                twice on a town overlay; full map coverage exposes exits as
                side effects.
              - walkOverworldTo(x, y): deterministic BFS on overworld toward (X, Y),
                aborts on encounter, honors fog blocks (failed warp tiles auto-
                blocked). Reserved for terrain traversal — town/castle entry tiles
                are hard-impassable unless they ARE the target.
              - battleFightAll: scripted FIGHT loop until battle ends; also
                dismisses PostBattle modal.
              - findPath / findPathToExit: read-only path queries.

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
              - V5.26+V5.29: interior navigation has exactly two tools —
                exitInterior (BFS to exit, fast on castles) and
                exploreInteriorFrontier (BFS to unvisited tile, robust on town
                overlays). Default escalation: exitInterior twice → if both
                fail, call exploreInteriorFrontier with maxSteps=64. Town
                exits typically emerge once the map is mostly covered.
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
              - V2.5.4 hard-impassable rule: T/C tiles are IMPASSABLE for
                walkOverworldTo unless they ARE the destination.
              - V5.27+V5.30 corollary: when the executor reports "trapped" on
                overworld with all neighbours either T/C glyphs or known-warp
                tiles, recommend ENTERING the closest one toward the goal.
                Both T/C and warp tiles can be passed as walkOverworldTo
                targets (V5.30 lets destination override fog blocks). Plan
                steps: (a) walkOverworldTo(entryX, entryY) into the
                town/castle, (b) exploreInteriorFrontier to cover the
                interior, (c) the runtime will exit on the far side once
                BFS finds an exit tile during exploration.
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
