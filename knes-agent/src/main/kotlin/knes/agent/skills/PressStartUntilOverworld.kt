package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Tap START until FF1's bootFlag (RAM 0x00F9) becomes 0x4D, indicating in-game state
 * after the title screen / NEW GAME confirmation. See profile ff1.json:28.
 *
 * Strategy: tap START, gap 30 frames, observe RAM. Up to maxAttempts. Falls back to A
 * after 10 unproductive START taps (intro cinematic sometimes wants A).
 */
class PressStartUntilOverworld(private val toolset: EmulatorToolset) : Skill {
    override val id = "press_start_until_overworld"
    override val description =
        "Tap START until the game advances past the title screen / NEW GAME menu. " +
            "Bounded by maxAttempts (default 60). Falls back to A after 10 START taps without progress."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxAttempts = args["maxAttempts"]?.toIntOrNull() ?: 60
        var attempts = 0
        var totalFrames = 0
        var unproductiveStarts = 0
        var lastBootFlag = toolset.getState().ram["bootFlag"] ?: 0
        while (attempts < maxAttempts) {
            val button = if (unproductiveStarts >= 10) "A" else "START"
            val tap = toolset.tap(button = button, count = 1, pressFrames = 5, gapFrames = 30)
            totalFrames += tap.frame
            attempts++
            val ram = toolset.getState().ram
            val bootFlag = ram["bootFlag"] ?: 0
            if (bootFlag == 0x4D) {
                return SkillResult(
                    ok = true,
                    message = "bootFlag flipped after $attempts taps",
                    framesElapsed = totalFrames,
                    ramAfter = ram,
                )
            }
            if (bootFlag == lastBootFlag) unproductiveStarts++ else unproductiveStarts = 0
            lastBootFlag = bootFlag
        }
        val ram = toolset.getState().ram
        return SkillResult(
            ok = false,
            message = "bootFlag never reached 0x4D after $maxAttempts taps",
            framesElapsed = totalFrames,
            ramAfter = ram,
        )
    }
}
