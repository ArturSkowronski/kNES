package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Walk DOWN (south) until the party exits the current building / town interior.
 *
 * FF1 has two indoor map types:
 *  - Castle/dungeon interior: locationType==0xD1
 *  - Town outdoor area (in-town map): locationType==0x00 but localX/Y populated
 *
 * V2.3.1: termination = back on overworld = locationType==0x00 AND localX==0 AND
 * localY==0. Walking DOWN exits both types (FF1 convention: south edge always exits).
 *
 * Bounded by maxSteps so we never loop forever in unusual interior layouts.
 */
class ExitBuilding(private val toolset: EmulatorToolset) : Skill {
    override val id = "exit_building"
    override val description =
        "Exit the current building / town / dungeon interior by walking SOUTH. " +
            "Terminates when RAM (locationType==0 AND localX==0 AND localY==0)."

    private val FRAMES_PER_TILE = 16

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 40
        var stepsTaken = 0
        var totalFrames = 0
        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            val onOverworld = (ram["locationType"] ?: 0) == 0x00 &&
                (ram["localX"] ?: 0) == 0 && (ram["localY"] ?: 0) == 0
            if (onOverworld) {
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
            message = "Did not exit interior in $maxSteps DOWN steps " +
                "(locationType=0x${(ram["locationType"] ?: 0).toString(16)}, " +
                "localX=${ram["localX"] ?: 0}, localY=${ram["localY"] ?: 0})",
            framesElapsed = totalFrames,
            ramAfter = ram,
        )
    }
}
