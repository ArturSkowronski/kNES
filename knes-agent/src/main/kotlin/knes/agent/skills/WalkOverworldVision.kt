package knes.agent.skills

import knes.agent.perception.OverworldMove
import knes.agent.perception.VisionOverworldNavigator
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset

/**
 * V5.18 — walks the FF1 overworld toward (targetX, targetY) by asking
 * a vision model for one cardinal direction per step. Skips the
 * deterministic BFS / [knes.agent.perception.OverworldTileClassifier]
 * pipeline entirely. Termination:
 *  - Reached: party world coords equal target → ok=true.
 *  - Interior entry: mapflags bit 0 → 1 → ok=true with "entered interior".
 *  - Encounter: screenState=0x68 → ok=true with "encounter".
 *  - Vision ENTERED: model declares interior view → ok=true.
 *  - Vision STUCK / UNCLEAR (consecutive threshold): ok=false.
 *  - maxSteps reached: ok=false.
 *
 * Mirrors [WalkInteriorVision] structure. Per HANDOFF #4 + V5.17:
 * the BFS-based [WalkOverworldTo] cannot reliably enter towns/castles
 * because the entry trigger is a ROM tileset_prop bit, not a byte-id.
 * This skill bypasses that with vision.
 */
class WalkOverworldVision(
    private val toolset: EmulatorToolset,
    private val navigator: VisionOverworldNavigator,
    private val toolCallLog: ToolCallLog? = null,
    private val framesPerTile: Int = 24,
) : Skill {
    override val id = "walk_overworld_vision"
    override val description =
        "Walk on the FF1 overworld toward (targetX, targetY) by asking a vision model for " +
            "one direction at a time. Bypasses the BFS pathfinder; useful when classifier " +
            "heuristics fail (e.g. town entry detection)."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args["targetX"]?.toIntOrNull() ?: return SkillResult(false, "missing targetX")
        val ty = args["targetY"]?.toIntOrNull() ?: return SkillResult(false, "missing targetY")
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 24
        val stuckThreshold = 2

        var totalFrames = 0
        var stepsTaken = 0
        var lastBlocked: OverworldMove? = null
        var consecutiveStuck = 0

        while (stepsTaken < maxSteps) {
            val ramPre = toolset.getState().ram
            if ((ramPre["screenState"] ?: 0) == 0x68) {
                return SkillResult(true,
                    "encounter triggered after $stepsTaken steps", totalFrames, ramPre)
            }
            val inStandardMap = ((ramPre["mapflags"] ?: 0) and 0x01) != 0
            if (inStandardMap) {
                return SkillResult(true,
                    "entered interior at world=(${ramPre["worldX"]},${ramPre["worldY"]}) " +
                        "mapId=${ramPre["currentMapId"]}",
                    totalFrames, ramPre)
            }
            val cx = ramPre["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ramPre["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "reached ($tx,$ty) in $stepsTaken steps", totalFrames, ramPre)
            }

            val frame = toolset.getState().frame
            val shotB64 = toolset.getScreen().base64
            val dir = navigator.nextDirection(
                shotB64, frame,
                partyWorldXY = cx to cy,
                targetWorldXY = tx to ty,
                hintLastBlocked = lastBlocked,
            )
            toolCallLog?.append("walkOverworldVision.dir",
                "step=$stepsTaken from=($cx,$cy) target=($tx,$ty) dir=${dir.name}" +
                    (lastBlocked?.let { " hintBlocked=${it.name}" } ?: ""))

            when (dir) {
                OverworldMove.ENTERED -> return SkillResult(true,
                    "vision says interior entered after $stepsTaken steps", totalFrames, ramPre)
                OverworldMove.STUCK, OverworldMove.UNCLEAR -> {
                    consecutiveStuck++
                    if (consecutiveStuck >= stuckThreshold) {
                        return SkillResult(false,
                            "vision returned ${dir.name} ${consecutiveStuck}x in a row " +
                                "after $stepsTaken steps at ($cx,$cy)",
                            totalFrames, ramPre)
                    }
                    // Fall through with a default cardinal toward target.
                    val dx = tx - cx; val dy = ty - cy
                    val fallback = when {
                        kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> if (dx > 0) OverworldMove.EAST else OverworldMove.WEST
                        else -> if (dy > 0) OverworldMove.SOUTH else OverworldMove.NORTH
                    }
                    toolCallLog?.append("walkOverworldVision.fallback",
                        "stuck=$consecutiveStuck dir->${fallback.name} (was ${dir.name})")
                    val r = toolset.step(buttons = listOf(fallback.button!!), frames = framesPerTile)
                    totalFrames += r.frame
                    stepsTaken++
                    val ramPost = toolset.getState().ram
                    val moved = (ramPost["worldX"] != cx) || (ramPost["worldY"] != cy)
                    val transitioned = ((ramPost["mapflags"] ?: 0) and 0x01) == 1 ||
                        (ramPost["screenState"] ?: 0) != (ramPre["screenState"] ?: 0)
                    lastBlocked = if (!moved && !transitioned) fallback else null
                    if (transitioned) {
                        return SkillResult(true,
                            "transitioned mid-loop at world=(${ramPost["worldX"]},${ramPost["worldY"]})",
                            totalFrames, ramPost)
                    }
                    continue
                }
                else -> consecutiveStuck = 0
            }

            val r = toolset.step(buttons = listOf(dir.button!!), frames = framesPerTile)
            totalFrames += r.frame
            stepsTaken++

            val ramPost = toolset.getState().ram
            val moved = (ramPost["worldX"] != cx) || (ramPost["worldY"] != cy)
            val transitioned = ((ramPost["mapflags"] ?: 0) and 0x01) == 1 ||
                (ramPost["screenState"] ?: 0) != (ramPre["screenState"] ?: 0)
            toolCallLog?.append("walkOverworldVision.step",
                "from=($cx,$cy) dir=${dir.name} → after=(${ramPost["worldX"]},${ramPost["worldY"]}) " +
                    "moved=$moved transitioned=$transitioned")
            lastBlocked = if (!moved && !transitioned) dir else null
            if (transitioned) {
                return SkillResult(true,
                    "transitioned mid-loop at world=(${ramPost["worldX"]},${ramPost["worldY"]})",
                    totalFrames, ramPost)
            }
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
