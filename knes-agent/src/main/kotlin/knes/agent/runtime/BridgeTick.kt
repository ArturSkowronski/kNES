package knes.agent.runtime

import knes.agent.perception.LandmarkMemory
import knes.agent.skills.SkillResult
import kotlin.math.absoluteValue

/**
 * Per-iteration tick for the post-BRIDGE phase. Drives the party from the
 * current overworld position toward the Temple of Fiends entrance using
 * `WalkOverworldTo`, falling back to a vision discover step when the entrance
 * coordinate is not yet known.
 *
 * Encounter handling is delegated to AgentSession.run()'s main-loop PostBattle
 * auto-dismiss path. WalkOverworldTo returns `ok=true, "encounter triggered..."`
 * when a random encounter aborts the walk; this tick treats that as a non-stall
 * Continue and re-tries on the next Overworld tick after combat resolves.
 *
 * Bail caps: 3 discover attempts, 5 consecutive walk no-progress steps. Bails
 * are sticky for the session lifetime to prevent thrash if BRIDGE re-fires.
 */
class BridgeTick(
    private val discover: suspend () -> SkillResult,
    private val walk: suspend (targetX: Int, targetY: Int) -> SkillResult,
    private val landmarks: LandmarkMemory,
) {
    sealed interface TickOutcome {
        data object Continue : TickOutcome
        data object Reached : TickOutcome
        data object BailToLlm : TickOutcome
    }

    var tofEntranceTile: Pair<Int, Int>? =
        landmarks.findTempleEntry()?.let { land ->
            val wx = land.worldX
            val wy = land.worldY
            if (wx != null && wy != null) wx to wy else null
        }
        private set

    var bailed: Boolean = false
        private set

    private var discoverAttempts: Int = 0
    private var walkStallCount: Int = 0

    private val DISCOVER_CAP = 3
    private val WALK_STALL_CAP = 5

    suspend fun run(ram: Map<String, Int>): TickOutcome {
        if (bailed) return TickOutcome.BailToLlm

        if (tofEntranceTile == null) {
            val r = discover()
            return if (r.message.startsWith("Discovered")) {
                // re-read landmark to get the persisted coords
                val land = landmarks.findTempleEntry()
                val wx = land?.worldX
                val wy = land?.worldY
                if (wx != null && wy != null) tofEntranceTile = wx to wy
                TickOutcome.Continue
            } else {
                discoverAttempts++
                if (discoverAttempts >= DISCOVER_CAP) {
                    bailed = true
                    TickOutcome.BailToLlm
                } else TickOutcome.Continue
            }
        }

        val (tx, ty) = tofEntranceTile!!
        val wx = ram["worldX"] ?: return TickOutcome.Continue
        val wy = ram["worldY"] ?: return TickOutcome.Continue
        val manhattan = (wx - tx).absoluteValue + (wy - ty).absoluteValue
        if (manhattan <= 1) return TickOutcome.Reached

        val r = walk(tx, ty)
        return when {
            r.ok && r.message.startsWith("encounter triggered") -> TickOutcome.Continue
            r.ok -> { walkStallCount = 0; TickOutcome.Continue }
            else -> {
                walkStallCount++
                if (walkStallCount >= WALK_STALL_CAP) {
                    bailed = true
                    TickOutcome.BailToLlm
                } else TickOutcome.Continue
            }
        }
    }
}
