package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportSource
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.tools.EmulatorToolset

/**
 * Walks toward (targetX, targetY) on the FF1 overworld using a deterministic
 * BFS pathfinder over the current viewport (decoded from FF1 ROM map data).
 * If the pathfinder finds a path (full or partial), the steps are pressed in
 * sequence. If a step does not move the party (RAM coords unchanged), the
 * target tile is marked blocked in the shared FogOfWar so future findPath
 * calls avoid it.
 */
class WalkOverworldTo(
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = ViewportPathfinder(),
) : Skill {
    override val id = "walk_overworld_to"
    override val description =
        "Walk on the FF1 overworld toward (targetX, targetY) via a deterministic BFS over the visible " +
            "16x16 viewport. Marks non-moving steps as blocked. Aborts on random encounter."

    private val FRAMES_PER_TILE = 24

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args["targetX"]?.toIntOrNull() ?: return SkillResult(false, "missing targetX")
        val ty = args["targetY"]?.toIntOrNull() ?: return SkillResult(false, "missing targetY")
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 32

        var totalFrames = 0
        var stepsTaken = 0

        while (stepsTaken < maxSteps) {
            val ram0 = toolset.getState().ram
            if ((ram0["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram0)
            }
            val cx = ram0["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ram0["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "reached ($tx,$ty) in $stepsTaken steps", totalFrames, ram0)
            }
            val viewport = viewportSource.readViewport(cx to cy)
            fog.merge(viewport)
            val path = pathfinder.findPath(cx to cy, tx to ty, viewport, fog)
            if (!path.found || path.steps.isEmpty()) {
                val ram = toolset.getState().ram
                return SkillResult(false,
                    "blocked at ($cx,$cy): ${path.reason ?: "no path"}", totalFrames, ram)
            }
            val nextDir = path.steps.first()
            val r = toolset.step(buttons = listOf(nextDir.button), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++
            val ram1 = toolset.getState().ram
            val nx = ram1["worldX"] ?: cx
            val ny = ram1["worldY"] ?: cy
            if (nx == cx && ny == cy) {
                fog.markBlocked(cx + nextDir.dx, cy + nextDir.dy)
            }
        }
        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
