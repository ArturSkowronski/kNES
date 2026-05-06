package knes.agent.runtime

import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset

/**
 * One-shot boot-phase orchestrator: handles the entry guards that decide whether
 * the outfit boot phase should run at all. Best-effort: any failure logs and
 * falls through to the strategic loop without bailing the session.
 *
 * Returns Result.skipped=true if the boot phase has nothing to do (already
 * bought, or party already equipped). Returns skipped=false + reason on
 * fall-through paths (e.g., no_town_entry) — caller logs and runs strategic
 * loop on whatever party state exists.
 *
 * Full per-character buy/equip orchestration lives in AgentSession.runOutfitBootPhase
 * which constructs the dependent skills.
 */
class OutfitBootPhase(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
    private val outfitState: OutfitState,
    private val savestateHash: String,
    private val trace: (String, String) -> Unit,
) {
    data class Result(val skipped: Boolean, val reason: String, val charsEquipped: List<Int> = emptyList())

    fun run(): Result {
        if (outfitState.weaponsBoughtFor(savestateHash)) {
            trace("boot_outfit_skip", "already-bought, hash matches")
            return Result(skipped = true, reason = "already-bought")
        }
        val ram = toolset.getState().ram
        val allEquipped = (1..4).all { StrategyContext.anyWeaponEquipped(ram, it) }
        if (allEquipped) {
            outfitState.markBought(savestateHash, listOf(1, 2, 3, 4), goldSpent = 0,
                shops = emptyList())
            trace("boot_outfit_skip", "ram shows all 4 already equipped")
            return Result(skipped = true, reason = "ram shows all 4 equipped",
                charsEquipped = listOf(1, 2, 3, 4))
        }
        val coneriaEntry = landmarks.findByKind(LandmarkKind.TOWN_ENTRY)
            .firstOrNull { it.note.contains("coneria", ignoreCase = true) }
            ?: landmarks.findByKind(LandmarkKind.TOWN_ENTRY).firstOrNull()
        if (coneriaEntry == null) {
            trace("boot_outfit_summary", "no_town_entry_landmark")
            return Result(skipped = false, reason = "no_town_entry")
        }
        // The full orchestration (walks, discover, buy, equip) lives in
        // AgentSession.runOutfitBootPhase which has access to all skills.
        // This unit returns a sentinel to indicate ready-for-compose.
        trace("boot_outfit_summary", "phase ready, delegating to AgentSession composer")
        return Result(skipped = false, reason = "ready_for_compose")
    }
}
