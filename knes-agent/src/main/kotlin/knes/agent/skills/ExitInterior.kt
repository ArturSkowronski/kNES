package knes.agent.skills

import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.InteriorObservation
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
    private val interiorMemory: InteriorMemory? = null,
) : Skill {
    override val id = "exit_interior"
    override val description =
        "Walk to the nearest exit of the current FF1 interior map. Stops on map " +
            "transition, encounter, or arrival on overworld."

    // V5.7: tile-precise movement via tap (press+release) instead of step (button held).
    // Holding a button for 48 frames lets the FF1 engine batch ~3 tiles per "step",
    // overshooting south-edge exits and pathfinder targets (V5.6 measurement showed
    // (11,32)→(8,32) in one iteration, missing exit at (9,32)). Tap pattern matches
    // ConeriaTownEmpiricalDiscoveryTest: press 6 frames, release 14, settle 8.
    private val PRESS_FRAMES = 6
    private val GAP_FRAMES = 14
    private val SETTLE_FRAMES = 8

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 64
        var totalFrames = 0
        var stepsTaken = 0

        try {
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
            interiorMemory?.record(mapId, partyX, partyY, InteriorObservation.VISITED)
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
            // V5.7: tap (press+release) instead of step (held). 1 tile per iteration.
            val tapResult = toolset.tap(
                button = nextDir.button,
                count = 1, pressFrames = PRESS_FRAMES, gapFrames = GAP_FRAMES,
            )
            val settleResult = toolset.step(buttons = emptyList(), frames = SETTLE_FRAMES)
            totalFrames += tapResult.frame + settleResult.frame
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

            // V5.9: detect transition out of interior, log EXIT_CONFIRMED with direction.
            val transitioned1 = ((ram1["mapflags"] ?: 0) and 0x01) == 0
            if (transitioned1) {
                interiorMemory?.record(
                    mapId, partyX, partyY, InteriorObservation.EXIT_CONFIRMED,
                    note = "exitDir=${nextDir.name}",
                )
            }

            // If party landed on STAIRS / WARP, tap A to activate transition.
            if (mid1 == mapId && (partyX1 != partyX || partyY1 != partyY)) {
                val tileNow = mapSession.currentMap?.classifyAt(partyX1, partyY1)
                interiorMemory?.record(mapId, partyX1, partyY1, InteriorObservation.VISITED)
                when (tileNow) {
                    TileType.STAIRS -> {
                        interiorMemory?.record(mapId, partyX1, partyY1, InteriorObservation.POI_STAIRS)
                        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
                    }
                    TileType.WARP -> {
                        interiorMemory?.record(mapId, partyX1, partyY1, InteriorObservation.POI_WARP)
                        toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
                    }
                    TileType.DOOR -> {
                        interiorMemory?.record(mapId, partyX1, partyY1, InteriorObservation.POI_DOOR)
                    }
                    else -> {}
                }
            }
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "did not exit interior in $maxSteps steps", totalFrames, ram)
        } finally {
            interiorMemory?.save()
        }
    }
}
