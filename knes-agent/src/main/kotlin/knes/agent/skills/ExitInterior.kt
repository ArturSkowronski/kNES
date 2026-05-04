package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.TileType
import knes.agent.pathfinding.InteriorPathfinder
import knes.agent.pathfinding.Pathfinder
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset

/**
 * Walks toward the nearest exit (DOOR / STAIRS / WARP or south-edge implicit exit)
 * of the current FF1 interior map using a deterministic BFS. Adapts to sub-map
 * transitions by reloading the map when currentMapId changes.
 *
 * Termination:
 *  - Success: phase becomes Overworld (mapflags bit 0 cleared).
 *  - Encounter: returns ok=true with reason "encounter".
 *  - No path: returns ok=false with current (mapId, partyX, partyY).
 */
class ExitInterior(
    private val toolset: EmulatorToolset,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = InteriorPathfinder(),
    private val toolCallLog: ToolCallLog? = null,
) : Skill {
    override val id = "exit_interior"
    override val description =
        "Walk to the nearest exit of the current FF1 interior map. Stops on map " +
            "transition, encounter, or arrival on overworld."

    private val FRAMES_PER_TILE = 48  // V2.4.5: 24 (overworld) still wasn't enough indoors. FF1 interior walk animation appears slower; 48 provides margin.

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 64
        var totalFrames = 0
        var stepsTaken = 0

        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            if ((ram["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram)
            }
            // V5.6: canonical 'on overworld' = mapflags bit 0 clear.
            val onOverworld = ((ram["mapflags"] ?: 0) and 0x01) == 0
            if (onOverworld) {
                return SkillResult(
                    true,
                    "reached overworld at (worldX=${ram["worldX"] ?: 0}, worldY=${ram["worldY"] ?: 0})",
                    totalFrames, ram,
                )
            }
            val mapId = ram["currentMapId"] ?: -1
            if (mapId < 0) {
                return SkillResult(false, "currentMapId unknown — RAM byte not configured", totalFrames, ram)
            }
            mapSession.ensureCurrent(mapId)
            // V5.6: party tile = ($0068, $0069) = sm_player_x/y per Disch disassembly.
            // Replaces V2.6.4's static (+8, +7) offset hack on $0029/$002A scroll, which
            // only worked when camera centered on party (broke at map edges).
            val partyX = ram["smPlayerX"] ?: 0
            val partyY = ram["smPlayerY"] ?: 0
            val viewport = mapSession.readFullMapView(partyX to partyY)
            fog.merge(viewport)
            val path = pathfinder.findPath(partyX to partyY, 0 to 0, viewport, fog)
            if (!path.found || path.steps.isEmpty()) {
                return SkillResult(
                    false,
                    "no exit visible at mapId=$mapId, party=($partyX,$partyY) " +
                        "scroll=(${ram["localX"]},${ram["localY"]}): ${path.reason ?: ""}",
                    totalFrames, ram,
                )
            }
            val nextDir = path.steps.first()
            val r = toolset.step(buttons = listOf(nextDir.button), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++

            val ram1 = toolset.getState().ram
            val mid1 = ram1["currentMapId"] ?: -1
            val partyX1 = ram1["smPlayerX"] ?: 0
            val partyY1 = ram1["smPlayerY"] ?: 0
            // V2.6.5: per-step trace so we can verify whether step actually moved party.
            toolCallLog?.append("exitInterior.step",
                "from=($partyX,$partyY) dir=${nextDir.name} → after=($partyX1,$partyY1) " +
                    "mapId=$mid1 pathLen=${path.steps.size}")
            if (mid1 == mapId && partyX1 == partyX && partyY1 == partyY) {
                fog.markBlocked(partyX + nextDir.dx, partyY + nextDir.dy)
            }

            // If party landed on STAIRS / WARP, tap A to activate transition.
            if (mid1 == mapId && (partyX1 != partyX || partyY1 != partyY)) {
                val tileNow = mapSession.currentMap?.classifyAt(partyX1, partyY1)
                if (tileNow == TileType.STAIRS || tileNow == TileType.WARP) {
                    toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
                }
            }
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "did not exit interior in $maxSteps steps", totalFrames, ram)
    }
}
