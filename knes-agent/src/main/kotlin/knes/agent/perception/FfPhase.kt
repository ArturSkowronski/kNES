package knes.agent.perception

sealed interface FfPhase {
    object Boot : FfPhase
    object TitleOrMenu : FfPhase
    data class Overworld(val x: Int, val y: Int) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase
    object PartyDefeated : FfPhase
}
