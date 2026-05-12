package knes.agent.v2.runtime

class Watchdog(
    private val thresholds: Map<Phase, Int> = DEFAULT_THRESHOLDS,
    private val staticWhitelist: Set<Phase> = PHASE_STATIC_WHITELIST,
) {
    private var stuck = 0
    private var lastRamHash: Int? = null

    /**
     * @param phase  current Phase classification
     * @param ramHash hash of watched RAM fields (worldX, worldY, currentMapId, hp..., gold, menuState)
     * @param skillProgress true if last executor outcome was Ok
     */
    fun observe(phase: Phase, ramHash: Int, skillProgress: Boolean) {
        if (phase in staticWhitelist) return
        val ramChanged = lastRamHash != null && ramHash != lastRamHash
        stuck = when {
            skillProgress -> 0
            ramChanged -> 0
            else -> stuck + 1
        }
        lastRamHash = ramHash
    }

    fun stuckSignal(phase: Phase): Boolean =
        stuck >= (thresholds[phase] ?: 5)

    fun threshold(phase: Phase): Int = thresholds[phase] ?: 5
    fun counter(): Int = stuck
    fun reset() { stuck = 0 }

    fun diagnose(phase: Phase, recentOutcomes: List<String>): String =
        "phase=$phase stuck=$stuck/${threshold(phase)} recentOutcomes=${recentOutcomes.takeLast(3)}"

    companion object {
        val DEFAULT_THRESHOLDS: Map<Phase, Int> = mapOf(
            Phase.Battle to 3, Phase.MenuStuck to 3,
            Phase.Overworld to 5, Phase.Indoors to 5,
            Phase.CartographerExplore to 10,
        )
    }
}
