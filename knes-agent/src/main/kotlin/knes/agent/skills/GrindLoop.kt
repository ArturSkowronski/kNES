package knes.agent.skills

import knes.agent.tools.EmulatorToolset
import kotlin.math.absoluteValue

/**
 * Walk N-S corridor near spawn until a random encounter triggers, the budget
 * is exhausted, or party drifts outside the corridor. Deterministic — no LLM
 * inside; AgentSession's PostBattle handler handles the resulting battle.
 *
 * Outcome encoded in [SkillResult.message] prefix:
 *   - "EncounteredBattle: ..."   ok=true
 *   - "NoEncounter: ..."         ok=true
 *   - "WanderedOff: ..."         ok=false
 *   - "Blocked: ..."             ok=false
 */
class GrindLoop(private val toolset: EmulatorToolset) : Skill {
    override val id = "grind_loop"
    override val description =
        "Walk N-S corridor near spawn (anchorX,anchorY ± corridorRadius) one step at a time. " +
        "Returns when a random encounter triggers (screenState=0x68) or after " +
        "maxStepsWithoutEncounter steps without one."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val anchorX = args["anchorX"]?.toIntOrNull() ?: 157
        val anchorY = args["anchorY"]?.toIntOrNull() ?: 158
        val corridorRadius = args["corridorRadius"]?.toIntOrNull() ?: 3
        val maxStepsWithoutEncounter = args["maxStepsWithoutEncounter"]?.toIntOrNull() ?: 6

        val driftLimit = corridorRadius * 2
        val startFrame = toolset.getState().frame
        var steps = 0
        var goingNorth = true

        while (steps < maxStepsWithoutEncounter) {
            val targetY = if (goingNorth) anchorY - corridorRadius else anchorY + corridorRadius
            toolset.tap(
                button = if (goingNorth) "UP" else "DOWN",
                count = 1, pressFrames = 5, gapFrames = 30
            )
            steps++

            val stateAfter = toolset.getState()
            val ram = stateAfter.ram
            val ss = ram["screenState"] ?: 0
            if (ss == 0x68 || ss == 0x63) {
                return SkillResult(
                    ok = true,
                    message = "EncounteredBattle: screenState=0x${ss.toString(16)} after $steps steps",
                    framesElapsed = stateAfter.frame - startFrame, ramAfter = ram,
                )
            }
            val mapId = ram["currentMapId"] ?: 0
            if (mapId != 0) {
                return SkillResult(
                    ok = false,
                    message = "Blocked: entered interior mapId=$mapId after $steps steps " +
                        "(world=(${ram["worldX"]},${ram["worldY"]}))",
                    framesElapsed = stateAfter.frame - startFrame, ramAfter = ram,
                )
            }
            val wx = ram["worldX"] ?: anchorX
            val wy = ram["worldY"] ?: anchorY
            if ((wx - anchorX).absoluteValue > driftLimit || (wy - anchorY).absoluteValue > driftLimit) {
                return SkillResult(
                    ok = false,
                    message = "WanderedOff: world=($wx,$wy) outside corridor anchor=($anchorX,$anchorY) " +
                        "± $driftLimit after $steps steps",
                    framesElapsed = stateAfter.frame - startFrame, ramAfter = ram,
                )
            }
            if ((goingNorth && wy <= targetY) || (!goingNorth && wy >= targetY)) {
                goingNorth = !goingNorth
            }
        }

        val stateAfter = toolset.getState()
        val ram = stateAfter.ram
        return SkillResult(
            ok = true,
            message = "NoEncounter: walked $steps steps without encounter " +
                "(world=(${ram["worldX"]},${ram["worldY"]}))",
            framesElapsed = stateAfter.frame - startFrame, ramAfter = ram,
        )
    }
}
