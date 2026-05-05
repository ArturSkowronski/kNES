package knes.agent.explorer

enum class StopReason { PartyWiped, UnknownMapTrap, Idle, LocalPlateau, MaxSteps, GoalAchievedEarly }

data class RunResult(val stopReason: StopReason, val haikuCostUsd: Double, val stepsTaken: Int)

/**
 * Inner loop of the explorer phase: one campaign run from the post-boot savestate
 * to a hard-rule stop. State (memory) flushed by ExplorerSession on completion.
 *
 * The companion object hosts pure functions ([checkRestart]) so the trigger
 * logic is unit-testable in isolation from emulator/observer.
 *
 * The full execute() method is added in Task 8 (this task ships the shell only).
 */
class SingleRun {
    companion object {
        /**
         * Hard-rule restart trigger. Pure function for testability; called once per turn
         * by SingleRun.execute(). Order matters — first match wins.
         *
         * @param ram RAM snapshot from observer.
         * @param knownMapIds mapIds where mapflags=1 is normal (Coneria Castle=1, Coneria Town=8).
         * @param consecutiveIdleInTrap how many turns we've been in mapflags=1 + unknown mapId.
         * @param idleTurns how many turns RAM has not changed.
         * @param stepsTaken how many turns into this run.
         * @param coverageWindow size of rolling window to evaluate "did we discover anything recently".
         * @param coverageDeltaInWindow tiles+landmarks newly discovered in last [coverageWindow] turns.
         */
        fun checkRestart(
            ram: Map<String, Int>,
            knownMapIds: Set<Int>,
            consecutiveIdleInTrap: Int,
            idleTurns: Int,
            stepsTaken: Int,
            coverageWindow: Int,
            coverageDeltaInWindow: Int,
        ): StopReason? {
            val mapflags = (ram["mapflags"] ?: 0) and 0x01
            val mapId = ram["currentMapId"] ?: -1
            val partyHpSum = (1..4).sumOf { ram["char${it}_hpLow"] ?: 0 }

            return when {
                partyHpSum == 0 && mapflags == 1 -> StopReason.PartyWiped
                mapflags == 1 && mapId !in knownMapIds && consecutiveIdleInTrap >= 3 -> StopReason.UnknownMapTrap
                idleTurns >= 10 -> StopReason.Idle
                coverageDeltaInWindow == 0 && stepsTaken > coverageWindow -> StopReason.LocalPlateau
                else -> null
            }
        }
    }
}
