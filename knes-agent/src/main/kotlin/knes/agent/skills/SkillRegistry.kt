package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.perception.FogOfWar
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.pathfinding.InteriorPathfinder
import knes.agent.pathfinding.Pathfinder
import knes.agent.pathfinding.ViewportPathfinder
import knes.agent.runtime.ToolCallLog
import knes.agent.tools.EmulatorToolset
import knes.agent.tools.results.ActionToolResult
import knes.agent.tools.results.StateSnapshot

@LLMDescription(
    "FF1 macro skills: scripted high-level actions that drive the emulator. Pick one per " +
        "outer turn; observe the resulting RAM state and choose the next skill."
)
class SkillRegistry(
    private val toolset: EmulatorToolset,
    private val overworldMap: OverworldMap,
    private val mapSession: MapSession,
    private val fog: FogOfWar,
    private val overworldPathfinder: Pathfinder = ViewportPathfinder(),
    private val interiorPathfinder: Pathfinder = InteriorPathfinder(),
    private val toolCallLog: ToolCallLog = ToolCallLog(),
) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val walkSkill = WalkOverworldTo(toolset, overworldMap, fog, overworldPathfinder, toolCallLog)
    private val exitInteriorSkill = ExitInterior(toolset, mapSession, fog, interiorPathfinder)

    @Tool
    @LLMDescription(
        "Advance from the FF1 title screen through NEW GAME / class select / name entry into " +
            "the overworld. Mashes START then A. Termination: char1_hpLow != 0 OR worldX != 0."
    )
    suspend fun pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult {
        toolCallLog.append("pressStartUntilOverworld", "maxAttempts=$maxAttempts")
        return pressStartSkill.invoke(mapOf("maxAttempts" to "$maxAttempts"))
    }

    @Tool
    @LLMDescription(
        "Walk to the nearest exit of the current FF1 interior map (DOOR/STAIRS/WARP or " +
            "south-edge implicit exit) using deterministic BFS. Stops on sub-map transition, " +
            "encounter, or arrival on overworld. Use when phase is Indoors."
    )
    suspend fun exitInterior(maxSteps: Int = 64): SkillResult {
        toolCallLog.append("exitInterior", "maxSteps=$maxSteps")
        return exitInteriorSkill.invoke(mapOf("maxSteps" to "$maxSteps"))
    }

    @Tool
    @LLMDescription(
        "Find walkable path from current local position to the nearest interior exit " +
            "(DOOR/STAIRS/WARP or south-edge) within the visible 16x16 viewport. " +
            "Deterministic; no LLM tokens."
    )
    fun findPathToExit(): String {
        toolCallLog.appendNoArgs("findPathToExit")
        val ram = toolset.getState().ram
        val mapId = ram["currentMapId"] ?: -1
        if (mapId < 0) return "BLOCKED. currentMapId unknown."
        mapSession.ensureCurrent(mapId)
        val from = (ram["localX"] ?: 0) to (ram["localY"] ?: 0)
        val viewport = mapSession.readViewport(from)
        fog.merge(viewport)
        val res = interiorPathfinder.findPath(from, 0 to 0, viewport, fog)
        return when {
            res.found -> "PATH ${res.steps.size} steps to exit at (${res.reachedTile.first}," +
                "${res.reachedTile.second}): ${res.steps.joinToString(",") { it.name }}"
            else -> "BLOCKED. ${res.reason ?: "no exit visible"}."
        }
    }

    @Tool
    @LLMDescription(
        "Walk on the FF1 overworld toward (targetX, targetY) using deterministic BFS pathfinding."
    )
    suspend fun walkOverworldTo(targetX: Int, targetY: Int, maxSteps: Int = 32): SkillResult {
        toolCallLog.append("walkOverworldTo", "targetX=$targetX, targetY=$targetY, maxSteps=$maxSteps")
        return walkSkill.invoke(mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps"))
    }

    @Tool
    @LLMDescription(
        "Find walkable path from current party position to target world coordinates within " +
            "the visible 16x16 overworld viewport."
    )
    fun findPath(targetX: Int, targetY: Int): String {
        toolCallLog.append("findPath", "targetX=$targetX, targetY=$targetY")
        val ram = toolset.getState().ram
        val from = (ram["worldX"] ?: 0) to (ram["worldY"] ?: 0)
        val viewport = overworldMap.readFullMapView(from)
        fog.merge(viewport)
        val res = overworldPathfinder.findPath(from, targetX to targetY, viewport, fog)
        return when {
            res.found && !res.partial ->
                "PATH ${res.steps.size} steps: ${res.steps.joinToString(",") { it.name }}"
            res.found && res.partial ->
                "PARTIAL ${res.steps.size} steps to (${res.reachedTile.first},${res.reachedTile.second}); " +
                    "first steps: ${res.steps.take(8).joinToString(",") { it.name }}"
            else -> "BLOCKED. ${res.reason ?: "no path"}. Suggest askAdvisor."
        }
    }

    @Tool
    @LLMDescription("Run the registered FF1 battle_fight_all action.")
    suspend fun battleFightAll(): ActionToolResult {
        toolCallLog.appendNoArgs("battleFightAll")
        return toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
    }

    @Tool
    @LLMDescription("Run the registered FF1 walk_until_encounter action.")
    suspend fun walkUntilEncounter(): ActionToolResult {
        toolCallLog.appendNoArgs("walkUntilEncounter")
        return toolset.executeAction(profileId = "ff1", actionId = "walk_until_encounter")
    }

    @Tool
    @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = toolset.getState()
}
