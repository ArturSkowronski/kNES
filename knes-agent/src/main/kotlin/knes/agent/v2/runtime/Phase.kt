package knes.agent.v2.runtime

enum class Phase {
    Boot, Overworld, Indoors, Battle, MenuStuck,
    Dialog, BattleMessage, Cutscene, CartographerExplore;

    companion object {
        /**
         * Classify from RAM. Reuses v1 phase markers; CartographerExplore is
         * set explicitly by Cartographer agent — never inferred here.
         */
        fun fromRam(ram: Map<String, Int>): Phase {
            val mapId = ram["currentMapId"] ?: -1
            val mapflags = ram["mapflags"] ?: 0
            val battleInProgress = (ram["battleState"] ?: 0) != 0
            val menuState = ram["menuState"] ?: 0
            val party = (ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0
            return when {
                !party -> Boot
                battleInProgress -> Battle
                menuState != 0 -> MenuStuck
                mapId == 0 && (mapflags and 1) == 0 -> Overworld
                else -> Indoors
            }
        }
    }
}

/** RAM fields stable across phase whitelist (used by Watchdog for "static is OK"). */
val PHASE_STATIC_WHITELIST: Set<Phase> = setOf(Phase.Dialog, Phase.BattleMessage, Phase.Cutscene)
