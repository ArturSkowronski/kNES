package knes.agent.perception

import knes.agent.tools.EmulatorToolset

class RamObserver(private val toolset: EmulatorToolset) {
    fun observe(): FfPhase = classify(toolset.getState().ram)

    fun ramSnapshot(): Map<String, Int> = toolset.getState().ram

    companion object {
        const val SCREEN_STATE_BATTLE = 0x68
        const val SCREEN_STATE_POST_BATTLE = 0x63

        // V2 fix (post-diagnostic): bootFlag is 0x4D within 9 frames of cold boot, useless.
        // Real markers:
        //   - TitleOrMenu when no party exists AND no overworld coords AND no battle screen
        //   - Battle when screenState == 0x68
        //   - PostBattle when screenState == 0x63
        //   - Overworld(x, y) when on overworld with party
        //   - PartyDefeated when all char_status flags have bit0 set
        fun classify(ram: Map<String, Int>): FfPhase {
            val screen = ram["screenState"] ?: 0
            if (screen == SCREEN_STATE_BATTLE) {
                return FfPhase.Battle(
                    enemyId = ram["enemyMainType"] ?: -1,
                    enemyHp = ((ram["enemy1_hpHigh"] ?: 0) shl 8) or (ram["enemy1_hpLow"] ?: 0),
                    enemyDead = (ram["enemy1_dead"] ?: 0) != 0,
                )
            }
            if (screen == SCREEN_STATE_POST_BATTLE) return FfPhase.PostBattle

            // Party-defeated: all 4 char_status have bit0 (KO) set, AND at least one char field exists.
            val charStatusKnown = (1..4).any { ram.containsKey("char${it}_status") }
            val charStatusValues = (1..4).map { ram["char${it}_status"] ?: 0 }
            val anyAlive = charStatusValues.any { (it and 0x01) == 0 }
            // char1_hpLow != 0 guard prevents pre-game state from being misread as PartyDefeated
            if (charStatusKnown && !anyAlive && (ram["char1_hpLow"] ?: 0) != 0) return FfPhase.PartyDefeated

            val partyCreated = (ram["char1_hpLow"] ?: 0) != 0
            val onWorldMap = (ram["worldX"] ?: 0) != 0 || (ram["worldY"] ?: 0) != 0

            return when {
                partyCreated && onWorldMap -> FfPhase.Overworld(ram["worldX"] ?: 0, ram["worldY"] ?: 0)
                else -> FfPhase.TitleOrMenu
            }
        }
    }
}
