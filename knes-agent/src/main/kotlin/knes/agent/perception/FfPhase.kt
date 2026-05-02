package knes.agent.perception

sealed interface FfPhase {
    object Boot : FfPhase { override fun toString() = "Boot" }
    object TitleOrMenu : FfPhase { override fun toString() = "TitleOrMenu" }
    object NewGameMenu : FfPhase { override fun toString() = "NewGameMenu" }
    object NameEntry : FfPhase { override fun toString() = "NameEntry" }
    data class Overworld(val x: Int, val y: Int) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase { override fun toString() = "PostBattle" }
    object PartyDefeated : FfPhase { override fun toString() = "PartyDefeated" }
}
