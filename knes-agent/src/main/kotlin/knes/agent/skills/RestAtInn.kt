package knes.agent.skills

import knes.agent.campaign.Ff1Campaign
import knes.agent.tools.EmulatorToolset

/**
 * Rest at the Coneria inn: assumes the party is ALREADY inside the inn interior
 * (currentMapId == innInteriorMapId). Taps A repeatedly until either:
 *   - gold drops AND minHp% reaches 100 → Rested
 *   - 30 taps elapse with no gold/hp change → InnNotFound
 *
 * Walk-to-inn is the caller's responsibility (AgentSession composes
 * walkOverworldTo + this skill).
 */
class RestAtInn(private val toolset: EmulatorToolset) : Skill {
    override val id = "rest_at_inn"
    override val description =
        "Tap A repeatedly inside the Coneria inn until heal completes (gold drops + HP=max) " +
        "or 30 taps elapse without change. Caller must be inside innInteriorMapId."

    private val maxTaps = 30

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val innInteriorMapId = args["innInteriorMapId"]?.toIntOrNull()
            ?: return SkillResult(ok = false,
                message = "InnNotFound: innInteriorMapId arg missing", ramAfter = emptyMap())

        val pre = toolset.getState().ram
        if ((pre["currentMapId"] ?: -1) != innInteriorMapId) {
            return SkillResult(ok = false,
                message = "InnNotFound: not inside inn (currentMapId=${pre["currentMapId"]} " +
                    "expected=$innInteriorMapId)", ramAfter = pre)
        }
        val preGold = Ff1Campaign.gold(pre)
        val preHpPct = Ff1Campaign.minHpPct(pre)
        val startFrame = toolset.getState().frame

        var taps = 0
        while (taps < maxTaps) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
            taps++
            val state = toolset.getState()
            val ram = state.ram
            val curGold = Ff1Campaign.gold(ram)
            val curHpPct = Ff1Campaign.minHpPct(ram)
            val framesElapsed = state.frame - startFrame
            if (curGold < preGold && curHpPct == 100 && preHpPct < 100) {
                return SkillResult(
                    ok = true,
                    message = "Rested: gold ${preGold}->${curGold} (cost=${preGold - curGold}), " +
                        "minHp% ${preHpPct}->${curHpPct} after $taps taps",
                    framesElapsed = framesElapsed, ramAfter = ram,
                )
            }
            // ~3-4 A presses dismiss the "party is fine, no payment" innkeeper dialog
            if (preHpPct == 100 && curGold == preGold && taps >= 4) {
                return SkillResult(
                    ok = true,
                    message = "Rested: party already at full HP, no payment ($taps taps)",
                    framesElapsed = framesElapsed, ramAfter = ram,
                )
            }
        }
        val final = toolset.getState()
        return SkillResult(
            ok = false,
            message = "InnNotFound: $maxTaps taps elapsed without gold/hp change " +
                "(gold=${Ff1Campaign.gold(final.ram)}, minHp%=${Ff1Campaign.minHpPct(final.ram)})",
            framesElapsed = final.frame - startFrame, ramAfter = final.ram,
        )
    }
}
