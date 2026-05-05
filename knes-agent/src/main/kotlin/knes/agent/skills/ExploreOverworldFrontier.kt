package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportSource
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset

/**
 * Deterministic salience-driven overworld walker for the explorer phase. Thin
 * wrapper over [WalkOverworldTo] with a simpler stop criterion: walk toward
 * (targetX, targetY) for up to maxSteps; abort cleanly on encounter, interior
 * entry, or BFS-blocked. The explorer's outer loop ([SingleRun]) chooses each
 * target via SalienceStrategy and re-invokes this skill on every overworld turn.
 */
class ExploreOverworldFrontier(
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = ViewportPathfinder(),
    private val toolCallLog: ToolCallLog? = null,
) : Skill {
    override val id = "explore_overworld_frontier"
    override val description =
        "Walk toward (targetX, targetY) on the overworld using deterministic BFS. " +
            "Aborts on encounter, interior entry, or no-path. Used by the explorer phase."

    private val walk = WalkOverworldTo(toolset, viewportSource, fog, pathfinder, toolCallLog)

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        // Delegate. WalkOverworldTo already handles encounter / interior abort / no-path.
        // The semantic difference is only that the explorer interprets results differently
        // (no panic recovery, no plan rewrite) — handled by SingleRun, not here.
        toolCallLog?.append("exploreOverworldFrontier",
            "targetX=${args["targetX"]}, targetY=${args["targetY"]}, maxSteps=${args["maxSteps"] ?: 32}")
        return walk.invoke(args)
    }
}
