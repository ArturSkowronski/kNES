package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.TileType
import knes.agent.pathfinding.InteriorPathfinder
import knes.agent.pathfinding.Pathfinder
import knes.agent.tools.EmulatorToolset

/**
 * Walks toward the nearest exit (DOOR / STAIRS / WARP or south-edge implicit exit)
 * of the current FF1 interior map using a deterministic BFS. Adapts to sub-map
 * transitions by reloading the map when currentMapId changes.
 *
 * Termination:
 *  - Success: phase becomes Overworld (locationType==0 AND localX==0 AND localY==0).
 *  - Encounter: returns ok=true with reason "encounter".
 *  - No path: returns ok=false with current (mapId, localX, localY).
 */
class ExitInterior(
    private val toolset: EmulatorToolset,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = InteriorPathfinder(),
) : Skill {
    override val id = "exit_interior"
    override val description =
        "Walk to the nearest exit of the current FF1 interior map. Stops on map " +
            "transition, encounter, or arrival on overworld."

    private val FRAMES_PER_TILE = 48  // V2.4.5: 24 (overworld) still wasn't enough indoors. FF1 interior walk animation appears slower; 48 provides margin.

    // V2.6.4: NES viewport is 16×15 tiles; party stays at center of screen.
    // RAM localX/localY = top-left of viewport (scroll offset). Party's actual
    // map tile = scroll + (8, 7).
    private val VIEWPORT_PARTY_OFFSET_X = 8
    private val VIEWPORT_PARTY_OFFSET_Y = 7

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 64
        var totalFrames = 0
        var stepsTaken = 0

        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            if ((ram["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram)
            }
            val onOverworld = (ram["locationType"] ?: 0) == 0 &&
                (ram["localX"] ?: 0) == 0 && (ram["localY"] ?: 0) == 0
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
            // V2.6.4: localX/localY in RAM are SCROLL OFFSET (top-left of 16×15 NES
            // viewport), not the party's tile. Party stands at center of screen; its
            // actual map tile = (localX + VIEWPORT_PARTY_OFFSET_X, localY + …Y).
            // Verified against mapId=24 dump: RAM (3, 2) + (8, 7) = (11, 9) which is
            // the throne-room corridor floor (byte 0x31 = passable). RAM (3, 2) raw
            // landed on (3, 2) = 0x3c padding which is why pre-V2.6.4 BFS exhausted.
            val partyX = (ram["localX"] ?: 0) + VIEWPORT_PARTY_OFFSET_X
            val partyY = (ram["localY"] ?: 0) + VIEWPORT_PARTY_OFFSET_Y
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
            val lx1 = ram1["localX"] ?: 0
            val ly1 = ram1["localY"] ?: 0
            val partyX1 = lx1 + VIEWPORT_PARTY_OFFSET_X
            val partyY1 = ly1 + VIEWPORT_PARTY_OFFSET_Y
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
