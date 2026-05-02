package knes.agent.perception

sealed interface FfPhase {
    object Boot : FfPhase { override fun toString() = "Boot" }
    object TitleOrMenu : FfPhase { override fun toString() = "TitleOrMenu" }
    object NewGameMenu : FfPhase { override fun toString() = "NewGameMenu" }
    object NameEntry : FfPhase { override fun toString() = "NameEntry" }
    data class Overworld(val x: Int, val y: Int) : FfPhase
    /** Inside a building / dungeon / town. `mapId` identifies the interior in ROM
     *  (-1 if RAM byte not yet identified). `localX` / `localY` are the party's
     *  position within that interior map. */
    data class Indoors(val mapId: Int, val localX: Int, val localY: Int) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase { override fun toString() = "PostBattle" }
    object PartyDefeated : FfPhase { override fun toString() = "PartyDefeated" }
}
