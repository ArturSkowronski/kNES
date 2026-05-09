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
            // 2026-05-09: town-overlay fallback. Coneria town renders as
            // mapId=0 + mapflags.bit0=1 — the overworld submap is loaded but
            // the engine draws the town overlay on top. The standard BFS
            // operates on mapId=0's tile data (overworld), which doesn't
            // represent the town's actual walls / NPCs / south exit, so it
            // emits bogus "exits" and the party walks in circles
            // (validated: 64 iters @ Coneria, world=(147,155), never crossed
            // mapflags=0). Bypass BFS for this case: blind-press Down with
            // settle frames, rotate cardinals when no world-coord progress.
            // Per memory `reference_ff1_npcs_move`, NPCs wander each frame so
            // a tile blocked one tap may be free the next.
            if (mapId == 0) {
                val (cardinalFrames, exited) = walkOutOfTownOverlay(maxTaps = maxSteps - stepsTaken)
                totalFrames += cardinalFrames
                val ramAfter = toolset.getState().ram
                return if (exited) {
                    SkillResult(true,
                        "town-overlay exit: reached overworld at (worldX=${ramAfter["worldX"] ?: 0}, " +
                            "worldY=${ramAfter["worldY"] ?: 0})",
                        totalFrames, ramAfter)
                } else {
                    SkillResult(false,
                        "town-overlay exit: did not clear mapflags after ${maxSteps - stepsTaken} cardinal taps " +
                            "(world=(${ramAfter["worldX"] ?: 0},${ramAfter["worldY"] ?: 0}))",
                        totalFrames, ramAfter)
                }
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

    /**
     * Town-overlay blind walker: prefer DOWN (towns are entered from the south,
     * so the south edge is the universal exit). On stuck (no world-coord change
     * for stuckThreshold consecutive taps), rotate to next cardinal.
     *
     * Returns (totalFrames, exited). exited=true iff mapflags bit 0 cleared.
     */
    private suspend fun walkOutOfTownOverlay(maxTaps: Int): Pair<Int, Boolean> {
        // 2026-05-09 cont 3 user-confirmed: "to exit the city you need to go down
        // from shop". DOWN is the universal Coneria-town exit direction. Earlier
        // runs (cont 3 #1-#3) tried rotating to LEFT/RIGHT after 3-6 stuck DOWN
        // taps; both LEFT (10,14) and RIGHT (12,14) at the deadlock point are
        // building doorways that open dialogs (mapflags.bit1=1) — exactly what
        // we don't want. Stay on DOWN. When stuck (NPC blocking per
        // `reference_ff1_npcs_move`), step idle frames to let the NPC wander
        // off, then retry DOWN. UP only as final fallback after exhausting
        // DOWN+wait cycles.
        val cardinals = listOf("DOWN", "UP")  // No LEFT/RIGHT — those are doorways in Coneria.
        var dirIdx = 0
        var stuckCount = 0
        val stuckThreshold = 3       // 3 stuck DOWN taps before idle-wait
        val maxIdleWaitsAt = 5       // up to 5 wait-and-retry cycles per stuck position
        var idleWaitsAt = 0
        val idleFrames = 30          // ~half a second wall clock for NPC drift
        var totalFrames = 0
        var taps = 0

        fun dump(tag: String) {
            try {
                val ram = toolset.getState().ram
                val shot = toolset.getScreen().base64
                val bytes = java.util.Base64.getDecoder().decode(shot)
                java.io.File("/tmp/spec5-town-exit-$tag.png").writeBytes(bytes)
                // Full watched-byte dump for dialog-signal hunt. The mid screenshot
                // (cont-3 #5) showed shop "Welcome" dialog open while mapflags=1,
                // so neither mapflags nor screenState toggled — need to find which
                // byte does. Dump ALL watched values; diff entry vs stuck offline.
                val allRam = ram.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
                println("[exit-town] $tag: $allRam")
            } catch (_: Throwable) { /* dev noise */ }
        }

        // Pre-flight: B-spam 8 taps to dismiss any lingering dialog (BuyAtShop's
        // 6 B-taps may not be enough if there's a "Welcome!" header above
        // BUY/SELL/EXIT). Then dump entry screenshot.
        // Run-3 evidence: post-buy mapflags=1, but after cardinal taps mapflags=3.
        // mapflags bit 1 likely = "dialog/menu active" — pressing DOWN walked
        // into the shopkeeper sprite and re-opened the dialog.
        repeat(8) {
            val t = toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
            val s = toolset.step(buttons = emptyList(), frames = 4)
            totalFrames += t.frame + s.frame
        }
        dump("entry")

        while (taps < maxTaps) {
            val ramPre = toolset.getState().ram
            val mapflagsPre = ramPre["mapflags"] ?: 0
            if ((mapflagsPre and 0x01) == 0) return totalFrames to true
            // If a dialog/menu is active (bit 1), B-tap to dismiss before moving.
            if ((mapflagsPre and 0x02) != 0) {
                val t = toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
                val s = toolset.step(buttons = emptyList(), frames = 4)
                totalFrames += t.frame + s.frame
                taps++
                continue
            }

            // V5.6 + 2026-05-09: in town overlay, smPlayerX/Y is the LOCAL coord
            // that actually moves; worldX/worldY tracks the overworld entry tile
            // and stays constant. Run-3's cardinal-taps used worldX/Y for stuck
            // detection — flagged stuck every tap, rotating direction unhelpfully.
            val smxPre = ramPre["smPlayerX"] ?: 0
            val smyPre = ramPre["smPlayerY"] ?: 0

            val tap = toolset.tap(button = cardinals[dirIdx], count = 1,
                pressFrames = PRESS_FRAMES, gapFrames = GAP_FRAMES)
            val settle = toolset.step(buttons = emptyList(), frames = SETTLE_FRAMES)
            totalFrames += tap.frame + settle.frame
            taps++

            if (taps == maxTaps / 2) dump("mid")

            val ramPost = toolset.getState().ram
            val mapflagsPost = ramPost["mapflags"] ?: 0
            if ((mapflagsPost and 0x01) == 0) return totalFrames to true

            val smxPost = ramPost["smPlayerX"] ?: 0
            val smyPost = ramPost["smPlayerY"] ?: 0
            println("[exit-town] tap#$taps dir=${cardinals[dirIdx]} " +
                "smPlayer=($smxPre,$smyPre)→($smxPost,$smyPost) " +
                "mapflags=$mapflagsPre→$mapflagsPost stuck=$stuckCount")

            if (smxPost == smxPre && smyPost == smyPre) {
                stuckCount++
                if (stuckCount >= stuckThreshold) {
                    if (idleWaitsAt == 0) {
                        // First time hitting stuckThreshold at this position — dump
                        // full RAM to hunt for the dialog-signal byte. Compare
                        // against the entry dump offline.
                        dump("stuck-${cardinals[dirIdx]}-$smxPost-$smyPost")
                    }
                    if (idleWaitsAt < maxIdleWaitsAt && cardinals[dirIdx] == "DOWN") {
                        // Wait for NPC to wander off the south-blocking tile.
                        val s = toolset.step(buttons = emptyList(), frames = idleFrames)
                        totalFrames += s.frame
                        idleWaitsAt++
                        stuckCount = 0
                        println("[exit-town] idle-wait $idleWaitsAt/$maxIdleWaitsAt at " +
                            "smPlayer=($smxPost,$smyPost) — NPC may be blocking south")
                    } else {
                        // Exhausted DOWN+wait budget; rotate cardinal (only UP left
                        // in our restricted list — typically gives up next iter).
                        dirIdx = (dirIdx + 1) % cardinals.size
                        stuckCount = 0
                        idleWaitsAt = 0
                    }
                }
            } else {
                stuckCount = 0
                idleWaitsAt = 0  // moved → reset wait budget for next stuck position
            }
        }
        dump("exit")
        return totalFrames to false
    }
}
