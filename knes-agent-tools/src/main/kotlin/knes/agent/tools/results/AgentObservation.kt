package knes.agent.tools.results

import kotlinx.serialization.Serializable

@Serializable
enum class AgentPhase {
    Boot,
    Overworld,
    Town,
    Indoors,
    Battle,
    MenuStuck,
    Unknown,
}

@Serializable
data class AgentPosition(
    val worldX: Int? = null,
    val worldY: Int? = null,
    val localX: Int? = null,
    val localY: Int? = null,
)

@Serializable
data class LocationHint(
    val id: String,
    val name: String,
    val confidence: Double,
    val reason: String,
)

@Serializable
data class AgentObservation(
    val frame: Int,
    val phase: AgentPhase,
    val position: AgentPosition,
    val location: LocationHint? = null,
    val ram: Map<String, Int>,
    val cpu: Map<String, Int>,
    val heldButtons: List<String>,
    val screenshot: ScreenPng? = null,
)

object AgentObservationBuilder {
    fun from(
        state: StateSnapshot,
        screen: ScreenPng? = null,
        profileId: String? = null,
    ): AgentObservation {
        val phase = phaseFromRam(state.ram)
        return AgentObservation(
            frame = state.frame,
            phase = phase,
            position = positionFromRam(state.ram),
            location = locationFromRam(profileId, phase, state.ram),
            ram = state.ram,
            cpu = state.cpu,
            heldButtons = state.heldButtons,
            screenshot = screen,
        )
    }

    private fun phaseFromRam(ram: Map<String, Int>): AgentPhase {
        val screenState = ram["screenState"] ?: 0
        val menuState = ram["menuState"] ?: 0
        val mapId = ram["currentMapId"] ?: -1
        val mapflags = ram["mapflags"] ?: 0
        val partyInitialized = (ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0

        return when {
            !partyInitialized -> AgentPhase.Boot
            screenState == FF1_BATTLE_SCREEN_STATE -> AgentPhase.Battle
            menuState != 0 -> AgentPhase.MenuStuck
            mapId == FF1_OVERWORLD_MAP_ID && (mapflags and FF1_TOWN_OVERLAY_FLAG) == 0 -> AgentPhase.Overworld
            mapId == FF1_OVERWORLD_MAP_ID && (mapflags and FF1_TOWN_OVERLAY_FLAG) != 0 -> AgentPhase.Town
            mapId >= 0 -> AgentPhase.Indoors
            else -> AgentPhase.Unknown
        }
    }

    private fun positionFromRam(ram: Map<String, Int>): AgentPosition =
        AgentPosition(
            worldX = ram["worldX"],
            worldY = ram["worldY"],
            localX = ram["smPlayerX"] ?: ram["localX"],
            localY = ram["smPlayerY"] ?: ram["localY"],
        )

    private fun locationFromRam(profileId: String?, phase: AgentPhase, ram: Map<String, Int>): LocationHint? {
        if (profileId?.lowercase() != "ff1") return null

        val worldX = ram["worldX"] ?: return null
        val worldY = ram["worldY"] ?: return null
        if (worldX !in FF1_CONERIA_WORLD_X || worldY !in FF1_CONERIA_WORLD_Y) return null

        return when (phase) {
            AgentPhase.Town ->
                LocationHint(
                    id = "ff1.coneria",
                    name = "Coneria",
                    confidence = 0.90,
                    reason = "FF1 town overlay near known Coneria world anchor",
                )

            AgentPhase.Overworld,
            AgentPhase.Battle ->
                LocationHint(
                    id = "ff1.coneria_region",
                    name = "Coneria region",
                    confidence = 0.80,
                    reason = "World coordinates match known Coneria starting region",
                )

            AgentPhase.Indoors ->
                LocationHint(
                    id = "ff1.coneria_interior",
                    name = "Coneria interior",
                    confidence = 0.70,
                    reason = "Interior while world anchor remains near Coneria",
                )

            else -> null
        }
    }

    private const val FF1_BATTLE_SCREEN_STATE = 0x68
    private const val FF1_OVERWORLD_MAP_ID = 0
    private const val FF1_TOWN_OVERLAY_FLAG = 1
    private val FF1_CONERIA_WORLD_X = 140..154
    private val FF1_CONERIA_WORLD_Y = 150..162
}
