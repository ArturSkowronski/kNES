package knes.agent.skills

import knes.agent.perception.InteriorFrontier
import knes.agent.perception.InteriorMemory
import knes.agent.perception.InteriorMove
import knes.agent.perception.InteriorObservation
import knes.agent.perception.MapSession
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset

/**
 * V5.29: deterministic interior explorer. Walks the party tile-by-tile toward
 * the nearest unvisited reachable tile in the current FF1 interior map. No
 * LLM in the step loop — uses [InteriorFrontier.nearestUnvisited] +
 * [InteriorMemory] (visited set) + [MapSession] (decoded interior view).
 *
 * Replacement for [WalkInteriorVision] in the default Indoors flow. Per the
 * user's architecture critique (point 6, "exploration as frontier search"):
 * give the planner an INTENT-level skill ("uncover this map") and let a
 * deterministic walker do the steps.
 *
 * Termination:
 *  - Exit: phase becomes Overworld (mapflags bit 0 cleared) — exits emerge
 *    as a side effect of full map coverage when the BFS finds them.
 *  - Encounter: returns ok=true with reason "encounter".
 *  - No frontier: every reachable tile already in InteriorMemory.visited.
 *    Returns ok=false with "interior fully explored, no exit found" so the
 *    runtime can hand back to the advisor.
 *  - Stuck: same direction blocked twice without movement → return ok=false.
 *  - maxSteps reached: ok=false.
 */
class ExploreInteriorFrontier(
    private val toolset: EmulatorToolset,
    private val mapSession: MapSession,
    private val interiorMemory: InteriorMemory,
    private val toolCallLog: ToolCallLog? = null,
    private val framesPerTile: Int = 48,
) : Skill {
    override val id = "explore_interior_frontier"
    override val description =
        "Walk an FF1 interior toward the nearest unvisited reachable tile."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 64
        var totalFrames = 0
        var stepsTaken = 0
        var lastBlockedDir: InteriorMove? = null
        var consecutiveBlocked = 0
        try {
            while (stepsTaken < maxSteps) {
                val ramPre = toolset.getState().ram
                if ((ramPre["screenState"] ?: 0) == 0x68) {
                    return SkillResult(true,
                        "encounter triggered after $stepsTaken steps", totalFrames, ramPre)
                }
                val onOverworld = ((ramPre["mapflags"] ?: 0) and 0x01) == 0
                if (onOverworld) {
                    return SkillResult(true,
                        "exited interior to overworld at (${ramPre["worldX"]},${ramPre["worldY"]})",
                        totalFrames, ramPre)
                }

                val mapId = ramPre["currentMapId"] ?: -1
                val partyX = ramPre["smPlayerX"] ?: 0
                val partyY = ramPre["smPlayerY"] ?: 0
                if (mapId < 0) {
                    return SkillResult(false, "currentMapId unknown", totalFrames, ramPre)
                }

                interiorMemory.record(mapId, partyX, partyY, InteriorObservation.VISITED)

                mapSession.ensureCurrent(mapId)
                val viewport = mapSession.readFullMapView(partyX to partyY)
                val visited = interiorMemory.visited(mapId)
                val frontier = InteriorFrontier.nearestUnvisited(
                    viewport, visited, from = partyX to partyY,
                )
                if (frontier == null) {
                    return SkillResult(false,
                        "interior fully explored at (mapId=$mapId, party=($partyX,$partyY)); " +
                            "no unvisited reachable tile and no exit found in $stepsTaken steps",
                        totalFrames, ramPre)
                }

                val dir = frontier.firstDirection
                toolCallLog?.append("exploreInteriorFrontier.target",
                    "step=$stepsTaken from=($partyX,$partyY) " +
                        "frontier=(${frontier.frontier.first},${frontier.frontier.second}) " +
                        "dist=${frontier.distance} dir=${dir.name}")

                if (dir == lastBlockedDir) {
                    consecutiveBlocked++
                    if (consecutiveBlocked >= 2) {
                        return SkillResult(false,
                            "stuck: frontier picks same blocked direction ${dir.name} " +
                                "from ($partyX,$partyY) in mapId=$mapId — visited=${visited.size}",
                            totalFrames, ramPre)
                    }
                } else {
                    consecutiveBlocked = 0
                }

                val button = dir.button ?: return SkillResult(false,
                    "frontier returned non-cardinal direction ${dir.name}", totalFrames, ramPre)
                val r = toolset.step(buttons = listOf(button), frames = framesPerTile)
                totalFrames += r.frame
                stepsTaken++

                val ramPost = toolset.getState().ram
                val moved = ramPost["smPlayerX"] != ramPre["smPlayerX"] ||
                            ramPost["smPlayerY"] != ramPre["smPlayerY"]
                val transitioned = ((ramPost["mapflags"] ?: 0) and 0x01) == 0
                toolCallLog?.append("exploreInteriorFrontier.step",
                    "from=($partyX,$partyY) " +
                        "after=(${ramPost["smPlayerX"]},${ramPost["smPlayerY"]}) " +
                        "moved=$moved transitioned=$transitioned")

                lastBlockedDir = if (!moved && !transitioned) dir else null

                if (transitioned) {
                    interiorMemory.record(mapId, partyX, partyY, InteriorObservation.EXIT_CONFIRMED,
                        note = "exitDir=${dir.name}")
                    return SkillResult(true,
                        "exited mid-loop at (${ramPost["worldX"]},${ramPost["worldY"]})",
                        totalFrames, ramPost)
                }
                if (moved) {
                    val partyXPost = ramPost["smPlayerX"] ?: partyX
                    val partyYPost = ramPost["smPlayerY"] ?: partyY
                    interiorMemory.record(mapId, partyXPost, partyYPost, InteriorObservation.VISITED)
                }
            }

            val ram = toolset.getState().ram
            return SkillResult(false,
                "walked $maxSteps frontier steps without exit (mapId=${ram["currentMapId"]})",
                totalFrames, ram)
        } finally {
            interiorMemory.save()
        }
    }
}
