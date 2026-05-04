package knes.agent.perception

sealed interface FfPhase {
    object Boot : FfPhase { override fun toString() = "Boot" }
    object TitleOrMenu : FfPhase { override fun toString() = "TitleOrMenu" }
    object NewGameMenu : FfPhase { override fun toString() = "NewGameMenu" }
    object NameEntry : FfPhase { override fun toString() = "NameEntry" }
    data class Overworld(val x: Int, val y: Int) : FfPhase
    /** Inside a building / dungeon / town. `mapId` identifies the interior in ROM
     *  (-1 if RAM byte not yet identified). `localX` / `localY` are the party's
     *  position within that interior map.
     *
     *  V5.4: `isTown` distinguishes outdoor towns (NPCs walking, no random encounters)
     *  from castle/dungeon interiors. Discriminator: locType=0x00 (town) vs 0xD1 (castle).
     *  Both populate $48=mapId and $29/$2A=local coords, but towns and castles use
     *  separate ROM pointer tables. */
    data class Indoors(val mapId: Int, val localX: Int, val localY: Int, val isTown: Boolean = false) : FfPhase
    data class Battle(val enemyId: Int, val enemyHp: Int, val enemyDead: Boolean) : FfPhase
    object PostBattle : FfPhase { override fun toString() = "PostBattle" }
    object PartyDefeated : FfPhase { override fun toString() = "PartyDefeated" }
}
