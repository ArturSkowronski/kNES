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
    /** V5.33: post-battle/post-anything input-dead warmup. The FF1 engine
     *  ignores button presses for ~30 frames after exiting Battle/PostBattle
     *  back to the overworld — same root cause as ExplorerSession V5.2
     *  post-loadState quirk. Without this, the very first step after a
     *  random encounter routinely fails, fog-marks the destination tile,
     *  and BFS self-poisons within 3 iterations. */
    private val WARMUP_FRAMES = 30

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args["targetX"]?.toIntOrNull() ?: return SkillResult(false, "missing targetX")
        val ty = args["targetY"]?.toIntOrNull() ?: return SkillResult(false, "missing targetY")
        // V5.34.1: bumped default 32→64. attempt6 evidence: BFS path from
        // (152,150) to bridge (157,141) was 22 steps but required routing
        // around peninsula water — single skill call exhausted maxSteps=32
        // mid-route, ITERATION_CAP at executor level fired. 64 covers the
        // longest realistic single-leg overworld walk (Coneria → Pravoka).
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 64

        // V5.33: NOOP warmup before first step. See WARMUP_FRAMES doc.
        toolset.step(buttons = emptyList(), frames = WARMUP_FRAMES)

        var totalFrames = WARMUP_FRAMES
        var stepsTaken = 0
        // V5.15: detect "input dead" (e.g. fixture loadState quirk) — if the
        // party doesn't move for several consecutive iterations, abort with a
        // diagnostic instead of pile-up fog.markBlocked, which self-poisons
        // the BFS until every cardinal neighbour is "blocked" and the
        // pathfinder reports closestReachable=start.
        var consecutiveNoMove = 0
        val INPUT_DEAD_THRESHOLD = 3

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
                val mapId = ram0["currentMapId"]
                // V5.20: distinguish targeted interior entry (success) from unexpected
                // mid-route entry (failure). When the agent asked for (tx,ty) and we
                // landed in an interior at exactly (tx,ty), that's the goal. When we
                // landed in an interior elsewhere, it's a navigation accident and the
                // executor needs to recover via exitInterior, not treat it as success.
                val targeted = cx0 == tx && cy0 == ty
                toolCallLog?.append("walkOverworldTo.aborted",
                    "entered interior after $stepsTaken steps; world=($cx0,$cy0) " +
                        "mapId=$mapId party=($px,$py) targeted=$targeted")
                return if (targeted) {
                    SkillResult(true,
                        "reached target interior at world=($cx0,$cy0) in $stepsTaken steps; " +
                            "mapId=$mapId party=($px,$py)",
                        totalFrames, ram0)
                } else {
                    SkillResult(false,
                        "UNEXPECTED interior entry at world=($cx0,$cy0) after $stepsTaken steps " +
                            "(target was ($tx,$ty)); mapId=$mapId party=($px,$py). " +
                            "Recover by calling exitInterior repeatedly to return to the overworld, " +
                            "then re-route around this tile.",
                        totalFrames, ram0)
                }
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
                consecutiveNoMove++
                // V5.33: defer fog.markBlocked until SECOND consecutive failure
                // on this attempt. First failure is often a transient input-
                // dead frame (post-battle, post-loadState, or sub-second engine
                // animation). Fog-marking on first fail self-poisons BFS until
                // 3-of-4 cardinal neighbours look "impassable" and the agent
                // declares a false deadlock — see attempt4 evidence at (152,151)
                // where (151,151) was fog-marked after a single failed W step
                // post-battle, then BFS reported "no path" on next iteration.
                if (consecutiveNoMove >= 2) {
                    fog.markBlocked(cx + nextDir.dx, cy + nextDir.dy)
                }
                if (consecutiveNoMove >= INPUT_DEAD_THRESHOLD) {
                    val ram = toolset.getState().ram
                    return SkillResult(false,
                        "input not responding: $consecutiveNoMove consecutive non-moving steps " +
                            "from ($cx,$cy) toward ($tx,$ty). " +
                            "Likely cause: fixture loadState quirk (V5.2) or party physically " +
                            "boxed in by terrain. Fog has been marked accordingly.",
                        totalFrames, ram)
                }
            } else {
                consecutiveNoMove = 0
            }
        }
        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
