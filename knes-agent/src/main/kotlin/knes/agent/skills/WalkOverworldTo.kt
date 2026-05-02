package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Greedy walk on FF1 overworld toward (targetX, targetY).
 *
 * Each step holds a direction button for FRAMES_PER_TILE frames (FF1 default 16).
 * If RAM screenState becomes 0x68 (battle), returns ok=true with message "encounter":
 * the agent's outer loop will see the Battle phase next observation.
 *
 * V2 uses greedy direction selection (no obstacle awareness). For boot→Coneria-bridge
 * this is sufficient because the path is roughly L-shaped in open overworld. If we hit
 * water/mountain, V3 should add A* over the walkable tile table.
 */
class WalkOverworldTo(private val toolset: EmulatorToolset) : Skill {
    override val id = "walk_overworld_to"
    override val description =
        "Walk on the FF1 overworld toward (targetX, targetY) greedily, one tile at a time. " +
            "Aborts on random encounter (returns ok=true so the outer loop handles the battle)."

    private val FRAMES_PER_TILE = 16

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val tx = args["targetX"]?.toIntOrNull() ?: return SkillResult(false, "missing targetX")
        val ty = args["targetY"]?.toIntOrNull() ?: return SkillResult(false, "missing targetY")
        val maxSteps = args["maxSteps"]?.toIntOrNull() ?: 200
        var stepsTaken = 0
        var totalFrames = 0
        while (stepsTaken < maxSteps) {
            val ram = toolset.getState().ram
            if ((ram["screenState"] ?: 0) == 0x68) {
                return SkillResult(true, "encounter triggered after $stepsTaken steps", totalFrames, ram)
            }
            val cx = ram["worldX"] ?: return SkillResult(false, "worldX missing")
            val cy = ram["worldY"] ?: return SkillResult(false, "worldY missing")
            if (cx == tx && cy == ty) {
                return SkillResult(true, "reached ($tx,$ty) in $stepsTaken steps", totalFrames, ram)
            }
            val dir = when {
                cx < tx -> "RIGHT"
                cx > tx -> "LEFT"
                cy < ty -> "DOWN"
                else -> "UP"
            }
            val r = toolset.step(buttons = listOf(dir), frames = FRAMES_PER_TILE)
            totalFrames += r.frame
            stepsTaken++
        }
        val ram = toolset.getState().ram
        return SkillResult(false, "did not reach ($tx,$ty) in $maxSteps steps", totalFrames, ram)
    }
}
