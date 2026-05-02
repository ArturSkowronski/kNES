package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Walk DOWN (south) until the party exits the current building / town interior.
 *
 * FF1 RAM `locationType` (0x000D) is `0xD1` while inside, `0x00` once outside on the
 * world map. Buildings in FF1 always have their exit on the south side; pressing DOWN
 * repeatedly is the canonical "leave any interior" action.
 *
 * Termination: locationType drops to 0x00 OR maxSteps exhausted.
 * Bounded so we never loop forever if the party is in an unusual interior layout.
 */
class ExitBuilding(private val toolset: EmulatorToolset) : Skill {
    override val id = "exit_building"
    override val description =
        "Exit the current building / town / dungeon interior by walking SOUTH. " +
            "Terminates when RAM locationType (0x000D) becomes 0x00 (outside)."

    private val FRAMES_PER_TILE = 16

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 30
        var stepsTaken = 0
        var totalFrames = 0
        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            if ((ram["locationType"] ?: 0) == 0x00) {
                return SkillResult(
                    ok = true,
                    message = "Exited to overworld at (worldX=0x${(ram["worldX"] ?: 0).toString(16)}, worldY=0x${(ram["worldY"] ?: 0).toString(16)}) after $stepsTaken steps",
                    framesElapsed = totalFrames,
                    ramAfter = ram,
                )
            }
            val r = toolset.step(buttons = listOf("DOWN"), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++
        }
        val ram = toolset.getState().ram
        return SkillResult(
            ok = false,
            message = "Did not exit interior in $maxSteps DOWN steps (locationType still 0x${(ram["locationType"] ?: 0).toString(16)})",
            framesElapsed = totalFrames,
            ramAfter = ram,
        )
    }
}
