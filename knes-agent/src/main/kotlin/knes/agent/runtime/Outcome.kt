package knes.agent.runtime

import knes.agent.perception.FfPhase

/**
 * Garland enemy id in FF1's enemy table. 0x7C is the canonical value used in
 * randomizer/community RAM maps; verify on the first acceptance run by logging
 * `enemyMainType` when the bridge battle starts and updating this constant if it differs.
 */
const val GARLAND_ID = 0x7C

enum class Outcome { InProgress, Victory, PartyDefeated, OutOfBudget, Error }

object SuccessCriteria {
    fun evaluate(phase: FfPhase): Outcome = when (phase) {
        is FfPhase.Battle -> if (phase.enemyId == GARLAND_ID && phase.enemyDead) Outcome.Victory else Outcome.InProgress
        FfPhase.PartyDefeated -> Outcome.PartyDefeated
        else -> Outcome.InProgress
    }
}
