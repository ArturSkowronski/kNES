package knes.agent.skills

import knes.agent.perception.InteriorFrontier
import knes.agent.perception.InteriorMemory
import knes.agent.perception.InteriorMove
import knes.agent.perception.InteriorObservation
import knes.agent.perception.MapSession
import knes.agent.perception.VisionInteriorNavigator
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset

/**
 * Walks the party out of an FF1 interior by asking a vision model for ONE direction
 * per step, then verifying movement via RAM. Drops the offline-decoder pathfinder
 * stack used by `ExitInterior` (V2.4–V2.6.5) which capped at 13% step success on
 * town maps. See `docs/superpowers/DECISION-2026-05-03-v3-vision-first-interior.md`.
 *
 * Termination:
 *  - Exit: phase becomes Overworld (mapflags bit 0 cleared).
 *  - Encounter: returns ok=true with reason "encounter".
 *  - Vision STUCK / UNCLEAR: returns ok=false; outer loop should ask the advisor.
 *  - maxSteps reached without exit: returns ok=false.
 */
class WalkInteriorVision(
    private val toolset: EmulatorToolset,
    private val navigator: VisionInteriorNavigator,
    private val toolCallLog: ToolCallLog? = null,
    private val interiorMemory: InteriorMemory? = null,
    private val mapSession: MapSession? = null,
    private val framesPerTile: Int = 48,  // matches V2.4.5 ExitInterior tuning
) : Skill {
    override val id = "walk_interior_vision"
    override val description =
        "Walk inside an FF1 interior map by asking a vision model for one direction at a time."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 24
        var totalFrames = 0
        var stepsTaken = 0
        var lastBlocked: InteriorMove? = null
        var consecutiveStuck = 0
        try {
        // V3.2: when navigator says STUCK on step 0 with no movement evidence, the
        // skill should not yet trust it. Default to SOUTH (FF1 castle/town entries
        // are at south edges) and reroll. Only honor STUCK after STUCK_THRESHOLD
        // consecutive returns — by then we have visual + RAM evidence the party is
        // actually pinned.
        val stuckThreshold = 2

        while (stepsTaken < maxSteps) {
            val ramPre = toolset.getState().ram
            if ((ramPre["screenState"] ?: 0) == 0x68) {
                return SkillResult(true,
                    "encounter triggered after $stepsTaken steps", totalFrames, ramPre)
            }
            // V5.6: canonical 'on overworld' = mapflags bit 0 clear (Disch).
            val onOverworld = ((ramPre["mapflags"] ?: 0) and 0x01) == 0
            if (onOverworld) {
                return SkillResult(true,
                    "exited interior to overworld at (${ramPre["worldX"]},${ramPre["worldY"]})",
                    totalFrames, ramPre)
            }
            // V5.9: record current interior tile as visited.
            val mapIdPre = ramPre["currentMapId"] ?: -1
            val partyXPre = ramPre["smPlayerX"] ?: 0
            val partyYPre = ramPre["smPlayerY"] ?: 0
            if (mapIdPre >= 0) {
                interiorMemory?.record(mapIdPre, partyXPre, partyYPre, InteriorObservation.VISITED)
            }

            val frame = toolset.getState().frame
            val shotB64 = toolset.getScreen().base64
            // V5.11: compute frontier hint when memory + mapSession are available.
            var frontierHint: InteriorMove? = null
            var unvisitedReachable = 0
            if (interiorMemory != null && mapSession != null && mapIdPre >= 0) {
                mapSession.ensureCurrent(mapIdPre)
                val viewport = mapSession.readFullMapView(partyXPre to partyYPre)
                val visited = interiorMemory.visited(mapIdPre)
                val frontier = InteriorFrontier.nearestUnvisited(
                    viewport, visited, from = partyXPre to partyYPre,
                )
                if (frontier != null) {
                    frontierHint = frontier.firstDirection
                    // 1 = "at least one unvisited reachable tile remains"; vision model
                    // only needs the binary signal, not an exact count.
                    unvisitedReachable = 1
                }
            }
            val dir = navigator.nextDirection(
                shotB64, frame, lastBlocked,
                frontierHint = frontierHint,
                unvisitedReachable = unvisitedReachable,
            )
            toolCallLog?.append("walkInteriorVision.dir",
                "step=$stepsTaken dir=${dir.name}" +
                    (lastBlocked?.let { " hintBlocked=${it.name}" } ?: ""))

            val effectiveDir: InteriorMove = when (dir) {
                InteriorMove.EXIT -> return SkillResult(true,
                    "vision says exited after $stepsTaken steps", totalFrames, ramPre)
                InteriorMove.STUCK, InteriorMove.UNCLEAR -> {
                    consecutiveStuck++
                    if (consecutiveStuck >= stuckThreshold) {
                        return SkillResult(false,
                            "vision returned ${dir.name} ${consecutiveStuck}x in a row " +
                                "after $stepsTaken steps", totalFrames, ramPre)
                    }
                    // V3.2 default: try SOUTH first (FF1 entries usually at south edge),
                    // unless that's what last failed — then try a perpendicular cardinal.
                    val fallback = when (lastBlocked) {
                        InteriorMove.SOUTH -> InteriorMove.WEST
                        InteriorMove.WEST -> InteriorMove.NORTH
                        InteriorMove.NORTH -> InteriorMove.EAST
                        InteriorMove.EAST -> InteriorMove.SOUTH
                        else -> InteriorMove.SOUTH
                    }
                    toolCallLog?.append("walkInteriorVision.fallback",
                        "stuck=$consecutiveStuck dir->${fallback.name} (was ${dir.name})")
                    fallback
                }
                else -> {
                    consecutiveStuck = 0
                    dir
                }
            }

            val r = toolset.step(buttons = listOf(effectiveDir.button!!), frames = framesPerTile)
            totalFrames += r.frame
            stepsTaken++

            val ramPost = toolset.getState().ram
            // V5.6: party movement = $0068/$0069 (sm_player) change, not scroll.
            val moved = ramPost["smPlayerX"] != ramPre["smPlayerX"] ||
                        ramPost["smPlayerY"] != ramPre["smPlayerY"]
            val transitioned = ((ramPost["mapflags"] ?: 0) and 0x01) == 0
            toolCallLog?.append("walkInteriorVision.step",
                "from=(${ramPre["smPlayerX"]},${ramPre["smPlayerY"]}) " +
                    "after=(${ramPost["smPlayerX"]},${ramPost["smPlayerY"]}) " +
                    "moved=$moved transitioned=$transitioned")

            lastBlocked = if (!moved && !transitioned) effectiveDir else null
            if (transitioned) {
                // V5.9: record exit-confirmed at the pre-step tile + direction.
                if (mapIdPre >= 0) {
                    interiorMemory?.record(
                        mapIdPre, partyXPre, partyYPre, InteriorObservation.EXIT_CONFIRMED,
                        note = "exitDir=${effectiveDir.name}",
                    )
                }
                return SkillResult(true,
                    "exited mid-loop at (${ramPost["worldX"]},${ramPost["worldY"]})",
                    totalFrames, ramPost)
            }
            // V5.9: record post-step interior tile as visited.
            if (moved && mapIdPre >= 0) {
                val partyXPost = ramPost["smPlayerX"] ?: partyXPre
                val partyYPost = ramPost["smPlayerY"] ?: partyYPre
                interiorMemory?.record(mapIdPre, partyXPost, partyYPost, InteriorObservation.VISITED)
            }
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "walked $maxSteps steps without exit", totalFrames, ram)
        } finally {
            interiorMemory?.save()
        }
    }
}
