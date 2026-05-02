package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.perception.FogOfWar
import knes.agent.perception.ViewportSource
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ActionToolResult
import knes.agent.tools.results.StateSnapshot

@LLMDescription(
    "FF1 macro skills: scripted high-level actions that drive the emulator. Pick one per " +
        "outer turn; observe the resulting RAM state and choose the next skill."
)
class SkillRegistry(
    private val toolset: EmulatorToolset,
    private val viewportSource: ViewportSource,
    private val fog: FogOfWar,
    private val pathfinder: Pathfinder = ViewportPathfinder(),
) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val walkSkill = WalkOverworldTo(toolset, viewportSource, fog, pathfinder)
    private val exitSkill = ExitBuilding(toolset)

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
        "Exit the current building / town / castle interior by walking SOUTH until RAM " +
            "locationType (0x000D) becomes 0x00 (outside). Use this when phase is Indoors."
    )
    suspend fun exitBuilding(maxSteps: Int = 30): SkillResult =
        exitSkill.invoke(mapOf("maxSteps" to "$maxSteps"))

    @Tool
    @LLMDescription(
        "Find a walkable path from current party position to target world coordinates within " +
            "the visible 16x16 viewport. Returns 'PATH n steps: D,D,...' if reachable, " +
            "'PARTIAL n steps to (x,y); target outside viewport' if partial, or 'BLOCKED reason' " +
            "if no path. Deterministic — does not consume LLM tokens."
    )
    fun findPath(targetX: Int, targetY: Int): String {
        val ram = toolset.getState().ram
        val from = (ram["worldX"] ?: 0) to (ram["worldY"] ?: 0)
        val viewport = viewportSource.readViewport(from)
        fog.merge(viewport)
        val res = pathfinder.findPath(from, targetX to targetY, viewport, fog)
        return when {
            res.found && !res.partial ->
                "PATH ${res.steps.size} steps: ${res.steps.joinToString(",") { it.name }}"
            res.found && res.partial ->
                "PARTIAL ${res.steps.size} steps to (${res.reachedTile.first},${res.reachedTile.second}); " +
                    "target outside viewport. Walk this path then call findPath again. " +
                    "First steps: ${res.steps.take(8).joinToString(",") { it.name }}"
            else -> "BLOCKED. ${res.reason ?: "no path"}. Suggest askAdvisor."
        }
    }

    @Tool
    @LLMDescription(
        "Walk on the FF1 overworld toward (targetX, targetY) using deterministic BFS pathfinding. " +
            "Marks non-moving steps as blocked in fog-of-war. Returns ok=true if the target is " +
            "reached OR a random encounter starts."
    )
    suspend fun walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 32): SkillResult =
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
