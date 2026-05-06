package knes.agent.skills

import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.runtime.StrategyContext
import knes.agent.tools.EmulatorToolset

/**
 * Probe the current building for inn behavior. Pre-condition: party is inside
 * a candidate building (currentMapId != 0). Taps A up to 30× watching RAM. If
 * gold drops AND minHp% reaches 100, persists an NPC_INNKEEPER landmark
 * (idempotent via recordIfNew) and returns Rested. Otherwise WrongBuilding.
 *
 * Caller (AgentSession REST handler) is responsible for entering the candidate
 * building and exiting on WrongBuilding to try the next candidate.
 *
 * See spec §9 for the autonomous-discovery flow.
 */
class DiscoverInn(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
    private val runId: String = "discover_inn",
) : Skill {
    override val id = "discover_inn"
    override val description =
        "Probe current building for inn behavior. Tap A up to 30x watching for " +
        "gold drop + HP=100. Persists NPC_INNKEEPER landmark on success."

    private val maxTaps = 30

    override suspend fun invoke(args: Map<String, String>): SkillResult {
        val pre = toolset.getState().ram
        val mapId = pre["currentMapId"] ?: 0
        if (mapId == 0) {
            return SkillResult(
                ok = false,
                message = "NotInBuilding: currentMapId=0 (still on overworld)",
                ramAfter = pre,
            )
        }
        val preGold = StrategyContext.totalGold(pre)
        val preHpPct = StrategyContext.minHpPct(pre)
        val startFrame = toolset.getState().frame

        var taps = 0
        while (taps < maxTaps) {
            toolset.tap(button = "A", count = 1, pressFrames = 5, gapFrames = 30)
            taps++
            val state = toolset.getState()
            val ram = state.ram
            val curGold = StrategyContext.totalGold(ram)
            val curHpPct = StrategyContext.minHpPct(ram)
            if (curGold < preGold && curHpPct == 100 && preHpPct < 100) {
                val cost = preGold - curGold
                val localX = ram["smPlayerX"] ?: 0
                val localY = ram["smPlayerY"] ?: 0
                val landmark = Landmark(
                    id = "innkeeper-map$mapId-$localX-$localY",
                    kind = LandmarkKind.NPC_INNKEEPER,
                    mapId = mapId, localX = localX, localY = localY,
                    note = "cost=$cost",
                    discoveredRunId = runId,
                )
                landmarks.recordIfNew(landmark)
                landmarks.save()
                return SkillResult(
                    ok = true,
                    message = "Rested: discovered innkeeper at map=$mapId local=($localX,$localY) " +
                        "cost=$cost (gold ${preGold}->${curGold}, hp% ${preHpPct}->100) after $taps taps",
                    framesElapsed = state.frame - startFrame,
                    ramAfter = ram,
                )
            }
        }
        val final = toolset.getState()
        return SkillResult(
            ok = false,
            message = "WrongBuilding: $maxTaps taps in mapId=$mapId without heal " +
                "(gold=${StrategyContext.totalGold(final.ram)}, hp%=${StrategyContext.minHpPct(final.ram)})",
            framesElapsed = final.frame - startFrame,
            ramAfter = final.ram,
        )
    }
}
