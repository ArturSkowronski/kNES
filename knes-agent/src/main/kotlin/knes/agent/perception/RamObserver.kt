package knes.agent.perception

import knes.agent.tools.EmulatorToolset

class RamObserver(private val toolset: EmulatorToolset) {
    fun observe(): FfPhase = classify(toolset.getState().ram)

    fun ramSnapshot(): Map<String, Int> = toolset.getState().ram

    companion object {
        const val SCREEN_STATE_BATTLE = 0x68
        const val SCREEN_STATE_POST_BATTLE = 0x63

        const val BOOT_FLAG_IN_GAME = 0x4D

        fun classify(ram: Map<String, Int>): FfPhase {
            // Pre-game (title screen, NEW GAME menu, party creation): bootFlag is not set.
            // Without this, worldX=0/worldY=0 (initial RAM) misclassifies as Overworld(0,0).
            val bootFlag = ram["bootFlag"]
            if (bootFlag != null && bootFlag != BOOT_FLAG_IN_GAME) return FfPhase.TitleOrMenu

            val partyDead = (1..4).all { (ram["char${it}_status"] ?: 0) and 0x01 == 0x01 }
            if (partyDead && (1..4).any { ram.containsKey("char${it}_status") }) return FfPhase.PartyDefeated

            return when (ram["screenState"]) {
                SCREEN_STATE_BATTLE -> FfPhase.Battle(
                    enemyId = ram["enemyMainType"] ?: -1,
                    enemyHp = ((ram["enemy1_hpHigh"] ?: 0) shl 8) or (ram["enemy1_hpLow"] ?: 0),
                    enemyDead = (ram["enemy1_dead"] ?: 0) != 0,
                )
                SCREEN_STATE_POST_BATTLE -> FfPhase.PostBattle
                else -> {
                    val x = ram["worldX"]; val y = ram["worldY"]
                    if (x != null && y != null) FfPhase.Overworld(x, y) else FfPhase.TitleOrMenu
                }
            }
        }
    }
}
