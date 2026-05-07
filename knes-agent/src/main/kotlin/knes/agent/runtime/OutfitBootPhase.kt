package knes.agent.runtime

import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.tools.EmulatorToolset

/**
 * One-shot boot-phase orchestrator: handles the entry guards that decide whether
 * the outfit boot phase should run at all. Best-effort: any failure logs and
 * falls through to the strategic loop without bailing the session.
 *
 * Skip semantics are derived from gameplay state only (RAM weapon-equipped flags
 * + landmarkMemory). No savestate-hash-keyed file flag.
 *
 * Returns Result.skipped=true when live RAM shows all 4 chars already equipped.
 * Returns skipped=false + reason on fall-through paths (e.g., no_town_entry) —
 * caller logs and runs strategic loop on whatever party state exists.
 *
 * Full per-character buy/equip orchestration lives in AgentSession.runOutfitBootPhase
 * which constructs the dependent skills.
 */
class OutfitBootPhase(
    private val toolset: EmulatorToolset,
    private val landmarks: LandmarkMemory,
    private val trace: (String, String) -> Unit,
) {
    data class Result(
        val skipped: Boolean,
        val reason: String,
        val charsEquipped: List<Int> = emptyList(),
        val coneriaEntry: knes.agent.perception.Landmark? = null,
    )

    fun run(): Result {
        val ram = toolset.getState().ram
        val allEquipped = (1..4).all { StrategyContext.anyWeaponEquipped(ram, it) }
        if (allEquipped) {
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
        return Result(skipped = false, reason = "ready_for_compose", coneriaEntry = coneriaEntry)
    }
}
