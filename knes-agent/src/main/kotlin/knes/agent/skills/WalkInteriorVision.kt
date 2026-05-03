package knes.agent.skills

import knes.agent.perception.InteriorMove
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
 *  - Exit: phase becomes Overworld (locationType==0 AND localX==0 AND localY==0).
 *  - Encounter: returns ok=true with reason "encounter".
 *  - Vision STUCK / UNCLEAR: returns ok=false; outer loop should ask the advisor.
 *  - maxSteps reached without exit: returns ok=false.
 */
class WalkInteriorVision(
    private val toolset: EmulatorToolset,
    private val navigator: VisionInteriorNavigator,
    private val toolCallLog: ToolCallLog? = null,
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

        while (stepsTaken < maxSteps) {
            val ramPre = toolset.getState().ram
            if ((ramPre["screenState"] ?: 0) == 0x68) {
                return SkillResult(true,
                    "encounter triggered after $stepsTaken steps", totalFrames, ramPre)
            }
            val onOverworld = (ramPre["locationType"] ?: 0) == 0 &&
                (ramPre["localX"] ?: 0) == 0 && (ramPre["localY"] ?: 0) == 0
            if (onOverworld) {
                return SkillResult(true,
                    "exited interior to overworld at (${ramPre["worldX"]},${ramPre["worldY"]})",
                    totalFrames, ramPre)
            }

            val frame = toolset.getState().frame
            val shotB64 = toolset.getScreen().base64
            val dir = navigator.nextDirection(shotB64, frame, lastBlocked)
            toolCallLog?.append("walkInteriorVision.dir",
                "step=$stepsTaken dir=${dir.name}" +
                    (lastBlocked?.let { " hintBlocked=${it.name}" } ?: ""))

            when (dir) {
                InteriorMove.EXIT -> return SkillResult(true,
                    "vision says exited after $stepsTaken steps", totalFrames, ramPre)
                InteriorMove.STUCK, InteriorMove.UNCLEAR -> return SkillResult(false,
                    "vision returned ${dir.name} after $stepsTaken steps", totalFrames, ramPre)
                else -> { /* fall through to tap */ }
            }

            val r = toolset.step(buttons = listOf(dir.button!!), frames = framesPerTile)
            totalFrames += r.frame
            stepsTaken++

            val ramPost = toolset.getState().ram
            val moved = ramPost["localX"] != ramPre["localX"] ||
                        ramPost["localY"] != ramPre["localY"]
            val transitioned = (ramPost["locationType"] ?: 0) == 0 &&
                               (ramPost["localX"] ?: 0) == 0 && (ramPost["localY"] ?: 0) == 0
            toolCallLog?.append("walkInteriorVision.step",
                "from=(${ramPre["localX"]},${ramPre["localY"]}) " +
                    "after=(${ramPost["localX"]},${ramPost["localY"]}) " +
                    "moved=$moved transitioned=$transitioned")

            lastBlocked = if (!moved && !transitioned) dir else null
            if (transitioned) {
                return SkillResult(true,
                    "exited mid-loop at (${ramPost["worldX"]},${ramPost["worldY"]})",
                    totalFrames, ramPost)
            }
        }

        val ram = toolset.getState().ram
        return SkillResult(false, "walked $maxSteps steps without exit", totalFrames, ram)
    }
}
