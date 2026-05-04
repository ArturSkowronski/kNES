package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportSource
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.runtime.ToolCallLog
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
    private val toolCallLog: ToolCallLog? = null,
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
            // V2.5.2: abort on interior entry. If party stepped onto a TOWN/CASTLE tile and
            // got transported into an interior map mid-walk, the overworld pathfinder is no
            // longer steering anything useful — return cleanly so outer loop can re-classify
            // phase and pick exitInterior.
            // V5.6: canonical 'in standard map' = mapflags ($2D) bit 0 per Disch.
            val inStandardMap = ((ram0["mapflags"] ?: 0) and 0x01) != 0
            if (inStandardMap) {
                val cx0 = ram0["worldX"] ?: -1
                val cy0 = ram0["worldY"] ?: -1
                val px = ram0["smPlayerX"] ?: 0
                val py = ram0["smPlayerY"] ?: 0
                toolCallLog?.append("walkOverworldTo.aborted",
                    "entered interior after $stepsTaken steps; world=($cx0,$cy0) " +
                        "mapId=${ram0["currentMapId"]} party=($px,$py)")
                return SkillResult(true,
                    "entered interior after $stepsTaken steps at world=($cx0,$cy0); " +
                        "mapId=${ram0["currentMapId"]} party=($px,$py)",
                    totalFrames, ram0)
            }
            val cx = ram0["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ram0["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "reached ($tx,$ty) in $stepsTaken steps", totalFrames, ram0)
            }
            // V2.5.4: pathfind over the full 256×256 overworld so the planner can
            // route around large blockers (e.g. Coneria town blob) instead of being
            // boxed into the 16×16 viewport.
            val viewport = viewportSource.readFullMapView(cx to cy)
            fog.merge(viewport)
            val path = pathfinder.findPath(cx to cy, tx to ty, viewport, fog)
            // V2.5.3/V2.5.8: per-pathfinder-call trace including reason on failure.
            toolCallLog?.append("walkOverworldTo.step",
                "from=($cx,$cy) found=${path.found} partial=${path.partial} " +
                    "len=${path.steps.size} dir=${path.steps.firstOrNull()?.name ?: "-"}" +
                    (if (!path.found || path.partial) " reason=${path.reason ?: "-"}" else ""))
            // V5.14: a partial path (found=true OR partial=true with steps) is
            // still progress toward the closestReachable tile. Bail only when
            // there are literally zero steps to take.
            if (path.steps.isEmpty()) {
                val ram = toolset.getState().ram
                val targetUnreach = path.targetPassable == false
                val reachedNote = path.closestReachable?.let { " (closest reachable: $it)" } ?: ""
                val reason = if (targetUnreach)
                    "target ($tx,$ty) is impassable terrain"
                else
                    path.reason ?: "no path"
                return SkillResult(false,
                    "stuck at ($cx,$cy): $reason$reachedNote", totalFrames, ram)
            }
            val nextDir = path.steps.first()
            val r = toolset.step(buttons = listOf(nextDir.button), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++
            val ram1 = toolset.getState().ram
            val nx = ram1["worldX"] ?: cx
            val ny = ram1["worldY"] ?: cy
            // V2.5.9: only mark fog blocked when the step is genuinely "I tried to walk
            // there and the engine refused because terrain". If RAM signals a transition
            // (mapflags / screenState changed), the world coords are frozen by design —
            // that tile is *passable*, we just got transported elsewhere.
            // V5.6: use mapflags as canonical interior-entry signal (replaces locType+lx/ly).
            val transitioned = ((ram1["mapflags"] ?: 0) and 0x01) != ((ram0["mapflags"] ?: 0) and 0x01) ||
                (ram1["screenState"] ?: 0) != (ram0["screenState"] ?: 0)
            if (nx == cx && ny == cy && !transitioned) {
                fog.markBlocked(cx + nextDir.dx, cy + nextDir.dy)
            }
        }
        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
