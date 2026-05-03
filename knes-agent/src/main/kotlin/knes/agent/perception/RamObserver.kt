package knes.agent.perception

import knes.agent.tools.EmulatorToolset

/**
 * Snapshot returned each turn. `viewportMap` is null for non-overworld phases
 * or when no OverworldMap is configured.
 */
data class Observation(
    val phase: FfPhase,
    val ram: Map<String, Int>,
    val viewportMap: ViewportMap?,
)

class RamObserver(
    private val toolset: EmulatorToolset,
    private val overworldMap: OverworldMap? = null,
    private val vision: VisionPhaseClassifier? = null,
) {
    fun observe(): FfPhase = classify(toolset.getState().ram)

    fun ramSnapshot(): Map<String, Int> = toolset.getState().ram

    /**
     * V2.5: vision-aware classification. Battle / PostBattle / PartyDefeated stay on RAM
     * (deterministic, zero-cost). For Title/Overworld/Indoors disambiguation — where V2.3.1's
     * `onLocalMap → Indoors` heuristic mis-classified the spawn-state — ask the vision
     * classifier with the current frame's screenshot. Falls back to RAM-only `classify` when
     * no vision classifier is configured (tests, offline runs).
     */
    suspend fun observeWithVision(): FfPhase {
        val ram = toolset.getState().ram
        deterministicFromRam(ram)?.let { return it }
        // V2.5.7: RAM hard-override for unambiguous overworld. Vision (Haiku 4.5) is
        // unreliable around towns/castles — same RAM signature was classified as
        // Indoors in 20/22 frames in V2.5.6 evidence. When RAM is clearly overworld
        // (locationType=0, no local coords, party exists, world coords valid), trust
        // RAM and skip vision. Vision is reserved for true ambiguity (spawn frame
        // where party-in-castle has overworld-zero locals due to RAM init quirks).
        val locType = ram["locationType"] ?: 0
        val lx = ram["localX"] ?: 0
        val ly = ram["localY"] ?: 0
        val wx = ram["worldX"] ?: 0
        val wy = ram["worldY"] ?: 0
        val partyCreated = (ram["char1_hpLow"] ?: 0) != 0
        val mapId = ram["currentMapId"] ?: 0
        if (partyCreated && locType == 0 && lx == 0 && ly == 0 && (wx != 0 || wy != 0)) {
            return FfPhase.Overworld(wx, wy)
        }
        // V3.1: extended hard-override. After walkOverworldTo aborts on a stale
        // interior-transition signal, FF1 leaves localX/Y non-zero on the world
        // map (RAM 0x0029/0x002A is "Non-world map position" per datacrystal —
        // FF1 never zeroes it on overworld entry). The strict V2.5.7 override
        // misses this case and the vision phase classifier (Haiku) flips to
        // false-Indoors, oscillating forever (V3.0 slice 1 evidence).
        // currentMapId=0 means "no interior loaded" — combined with locType=0
        // and live world coords, the party is unambiguously on the world map.
        if (partyCreated && locType == 0 && mapId == 0 && (wx != 0 || wy != 0)) {
            return FfPhase.Overworld(wx, wy)
        }
        val v = vision ?: return classify(ram)  // RAM-only fallback (legacy V2.3.1 heuristic)
        val state = toolset.getState()
        val shot = toolset.getScreen().base64
        return when (v.classify(shot, state.frame)) {
            PhaseHint.OVERWORLD -> FfPhase.Overworld(ram["worldX"] ?: 0, ram["worldY"] ?: 0)
            PhaseHint.INDOORS -> FfPhase.Indoors(
                mapId = ram["currentMapId"] ?: -1,
                localX = ram["localX"] ?: 0,
                localY = ram["localY"] ?: 0,
            )
            PhaseHint.TITLE -> FfPhase.TitleOrMenu
            PhaseHint.UNKNOWN -> classify(ram)  // vision failed → fall back
        }
    }

    /** Full observation including viewport (when phase is Overworld and map is wired). */
    fun observeFull(): Observation {
        val ram = toolset.getState().ram
        val phase = classify(ram)
        val vm = if (phase is FfPhase.Overworld && overworldMap != null) {
            overworldMap.readViewport(partyWorldXY = phase.x to phase.y)
        } else null
        return Observation(phase, ram, vm)
    }

    /** Classifications that don't need a screen — Battle/PostBattle/PartyDefeated. */
    private fun deterministicFromRam(ram: Map<String, Int>): FfPhase? {
        val screen = ram["screenState"] ?: 0
        if (screen == SCREEN_STATE_BATTLE) return FfPhase.Battle(
            enemyId = ram["enemyMainType"] ?: -1,
            enemyHp = ((ram["enemy1_hpHigh"] ?: 0) shl 8) or (ram["enemy1_hpLow"] ?: 0),
            enemyDead = (ram["enemy1_dead"] ?: 0) != 0,
        )
        if (screen == SCREEN_STATE_POST_BATTLE) return FfPhase.PostBattle
        val charStatusKnown = (1..4).any { ram.containsKey("char${it}_status") }
        val charStatusValues = (1..4).map { ram["char${it}_status"] ?: 0 }
        val anyAlive = charStatusValues.any { (it and 0x01) == 0 }
        if (charStatusKnown && !anyAlive && (ram["char1_hpLow"] ?: 0) != 0) return FfPhase.PartyDefeated
        return null
    }

    companion object {
        const val SCREEN_STATE_BATTLE = 0x68
        const val SCREEN_STATE_POST_BATTLE = 0x63
        const val LOCATION_TYPE_INDOORS = 0xD1

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

            val charStatusKnown = (1..4).any { ram.containsKey("char${it}_status") }
            val charStatusValues = (1..4).map { ram["char${it}_status"] ?: 0 }
            val anyAlive = charStatusValues.any { (it and 0x01) == 0 }
            if (charStatusKnown && !anyAlive && (ram["char1_hpLow"] ?: 0) != 0) return FfPhase.PartyDefeated

            val partyCreated = (ram["char1_hpLow"] ?: 0) != 0
            val localX = ram["localX"] ?: 0
            val localY = ram["localY"] ?: 0
            val onLocalMap = localX != 0 || localY != 0
            // V2.3.1: locationType==0xD1 is castle/dungeon interior. Town outdoor maps
            // (e.g. Coneria) have locationType==0 but populate localX/localY anyway.
            // Treat any non-zero local coords as Indoors — the exitBuilding skill (walks
            // SOUTH until both worldX/Y and locationType reset) handles both castle exits
            // AND town exits uniformly.
            if (partyCreated && (
                    (ram["locationType"] ?: 0) == LOCATION_TYPE_INDOORS ||
                    onLocalMap
                )) {
                return FfPhase.Indoors(
                    mapId = ram["currentMapId"] ?: -1,
                    localX = localX,
                    localY = localY,
                )
            }

            val onWorldMap = (ram["worldX"] ?: 0) != 0 || (ram["worldY"] ?: 0) != 0
            return when {
                partyCreated && onWorldMap -> FfPhase.Overworld(ram["worldX"] ?: 0, ram["worldY"] ?: 0)
                else -> FfPhase.TitleOrMenu
            }
        }
    }
}
