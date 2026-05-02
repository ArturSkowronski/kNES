package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ActionToolResult
import knes.agent.tools.results.StateSnapshot

/**
 * V2's reduced LLM-facing tool surface (spec §5).
 *
 *   pressStartUntilOverworld                                      — V2 skill (mashes through title→party→overworld)
 *   walkOverworldTo                                               — V2 skill (greedy directional walk)
 *   battleFightAll / walkUntilEncounter                           — wrappers around existing ff1 GameActions
 *   getState                                                      — read-only state snapshot
 *
 * `askAdvisor` is registered separately by the executor (it lives on the advisor side; not in this ToolSet).
 *
 * `CreateDefaultParty` was intentionally skipped — `PressStartUntilOverworld` already drives the
 * full title→party→overworld sequence by A-mashing through default class confirmations. If V3
 * needs deliberate class selection, add `CreateDefaultParty` then.
 *
 * Raw step/tap/sequence/press/release/loadRom/reset/applyProfile remain on EmulatorToolset
 * (used by Skill implementations and by the Ktor / MCP layers) but are NOT in this ToolSet.
 */
@LLMDescription(
    "FF1 macro skills: scripted high-level actions that drive the emulator. Pick one per " +
        "outer turn; observe the resulting RAM state and choose the next skill."
)
class SkillRegistry(private val toolset: EmulatorToolset) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val walkSkill = WalkOverworldTo(toolset)

    @Tool
    @LLMDescription(
        "Advance from the FF1 title screen through NEW GAME / class select / name entry into " +
            "the overworld. Mashes START then A. Termination: char1_hpLow != 0 OR worldX != 0. " +
            "Bounded by maxAttempts (default 60)."
    )
    suspend fun pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult =
        pressStartSkill.invoke(mapOf("maxAttempts" to "$maxAttempts"))

    @Tool
    @LLMDescription(
        "Walk on the FF1 overworld toward (targetX, targetY) greedily, one tile at a time. " +
            "Returns ok=true if the target is reached OR a random encounter starts."
    )
    suspend fun walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 200): SkillResult =
        walkSkill.invoke(mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription("Run the registered FF1 battle_fight_all action: every alive character uses FIGHT until the battle ends.")
    suspend fun battleFightAll(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")

    @Tool
    @LLMDescription("Run the registered FF1 walk_until_encounter action: walk randomly until a battle starts.")
    suspend fun walkUntilEncounter(): ActionToolResult =
        toolset.executeAction(profileId = "ff1", actionId = "walk_until_encounter")

    @Tool
    @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = toolset.getState()
}
