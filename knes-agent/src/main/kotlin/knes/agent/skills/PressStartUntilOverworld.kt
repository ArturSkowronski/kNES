package knes.agent.skills

import knes.agent.tools.EmulatorToolset

/**
 * Advance from the FF1 title screen through NEW GAME / class select / name entry
 * into the overworld. Taps START twice to exit the title attract, then mashes A
 * until the party is created and on the overworld.
 *
 * Termination: char1_hpLow != 0 OR worldX != 0. Bounded by maxAttempts (default 60).
 *
 * V2 fix: replaced broken bootFlag heuristic (bootFlag=0x4D within 9 frames of cold boot)
 * with real RAM markers: worldX/char1_hpLow populated only after party is created.
 */
class PressStartUntilOverworld(private val toolset: EmulatorToolset) : Skill {
    override val id = "press_start_until_overworld"
    override val description =
        "Advance from the FF1 title screen through NEW GAME / class select / name entry " +
            "into the overworld. Mashes START then A. Termination: char1_hpLow != 0 OR worldX != 0. " +
            "Bounded by maxAttempts (default 60)."

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val maxAttempts = args["maxAttempts"]?.toIntOrNull() ?: 60
        var attempts = 0
        var totalFrames = 0

        // Phase 1: tap START twice to exit the title attract.
        repeat(2) {
            val tap = toolset.tap(button = "START", count = 1, pressFrames = 5, gapFrames = 30)
            totalFrames += tap.frame
            attempts++
        }

        // Phase 2: tap A until the party is created and on the overworld.
        while (attempts < maxAttempts) {
            val ram = toolset.getState().ram
            val onOverworld = (ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0
            if (onOverworld) {
                return SkillResult(
                    ok = true,
                    message = "Reached overworld after $attempts taps " +
                        "(worldX=0x${(ram["worldX"] ?: 0).toString(16)}, char1_hp=0x${(ram["char1_hpLow"] ?: 0).toString(16)})",
                    framesElapsed = totalFrames,
                    ramAfter = ram,
                )
            }
            val tap = toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
            totalFrames += tap.frame
            attempts++
        }
        val ram = toolset.getState().ram
        return SkillResult(
            ok = false,
            message = "Did not reach overworld after $maxAttempts taps " +
                "(menuCursor=0x${(ram["menuCursor"] ?: 0).toString(16)}, " +
                "worldX=0x${(ram["worldX"] ?: 0).toString(16)}, " +
                "char1_hpLow=0x${(ram["char1_hpLow"] ?: 0).toString(16)})",
            framesElapsed = totalFrames,
            ramAfter = ram,
        )
    }
}
