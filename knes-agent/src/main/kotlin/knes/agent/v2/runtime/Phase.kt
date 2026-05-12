package knes.agent.v2.runtime

enum class Phase {
    Boot, Overworld, Town, Indoors, Battle, MenuStuck,
    Dialog, BattleMessage, Cutscene, CartographerExplore;

    companion object {
        /**
         * Classify from RAM. Reuses v1 phase markers; CartographerExplore is
         * set explicitly by Cartographer agent — never inferred here.
         *
         * Town vs Indoors: per `reference_ff1_shop_architecture.md`, FF1 NES
         * town overlays (Coneria, Pravoka, …) live at mapId=0 with mapflags
         * bit0=1 — they share the overworld map id but render town tiles.
         * True building interiors (sub-shops, castles, dungeons) have mapId>0.
         * Distinguishing matters for walkTo dispatch: Indoors → exitInterior
         * (bounce out), but Town must NOT bounce — the agent needs to reach
         * an NPC inside before any buy/inn skill can succeed (see Smoke 0
         * trace T2-T5 in `2026-05-12-v2-smoke-0-handoff.md`).
         */
        fun fromRam(ram: Map<String, Int>): Phase {
            val mapId = ram["currentMapId"] ?: -1
            val mapflags = ram["mapflags"] ?: 0
            // Battle detection: the ff1 RAM profile doesn't expose a
            // canonical "battleState" byte. The reliable signals are
            // screenState=0x68 (battle screen drawn) and battleTurn>0.
            // Smoke 1 v6 evidence: 12 turns sat in battle (screenState=104,
            // battleTurn=2, enemyCount=5) but Phase reported Overworld
            // because the `battleState` lookup missed — blocking Gap #1's
            // battleFightAll gate on a legitimate battle.
            val screenState = ram["screenState"] ?: 0
            val battleInProgress = screenState == 0x68 ||
                (ram["battleTurn"] ?: 0) > 0 ||
                (ram["enemyCount"] ?: 0) > 0
            val menuState = ram["menuState"] ?: 0
            val party = (ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0
            return when {
                !party -> Boot
                battleInProgress -> Battle
                menuState != 0 -> MenuStuck
                mapId == 0 && (mapflags and 1) == 0 -> Overworld
                mapId == 0 && (mapflags and 1) == 1 -> Town
                else -> Indoors
            }
        }
    }
}

/** RAM fields stable across phase whitelist (used by Watchdog for "static is OK"). */
val PHASE_STATIC_WHITELIST: Set<Phase> = setOf(Phase.Dialog, Phase.BattleMessage, Phase.Cutscene)
