package knes.agent.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import knes.agent.perception.FogOfWar
import knes.agent.perception.InteriorMemory
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldMap
import knes.agent.perception.VisionInteriorNavigator
import knes.agent.perception.VisionOverworldNavigator
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
    private val toolCallLog: ToolCallLog = ToolCallLog(),
    private val visionInteriorNavigator: VisionInteriorNavigator? = null,
    private val visionOverworldNavigator: VisionOverworldNavigator? = null,
    private val interiorMemory: InteriorMemory = InteriorMemory(),
    private val interiorPathfinder: Pathfinder = InteriorPathfinder(
        memory = interiorMemory,
        mapIdProvider = { toolset.getState().ram["currentMapId"] ?: -1 },
    ),
    private val landmarks: LandmarkMemory = LandmarkMemory(),
) : ToolSet {

    private val pressStartSkill = PressStartUntilOverworld(toolset)
    private val walkSkill = WalkOverworldTo(toolset, overworldMap, fog, overworldPathfinder, toolCallLog)
    private val exitInteriorSkill =
        ExitInterior(toolset, mapSession, fog, interiorPathfinder, toolCallLog, interiorMemory)
    private val walkInteriorVisionSkill = visionInteriorNavigator?.let {
        WalkInteriorVision(toolset, it, toolCallLog, interiorMemory, mapSession)
    }
    private val walkOverworldVisionSkill = visionOverworldNavigator?.let {
        WalkOverworldVision(toolset, it, toolCallLog)
    }
    private val exploreInteriorFrontierSkill =
        ExploreInteriorFrontier(toolset, mapSession, interiorMemory, toolCallLog)

    @Tool
    @LLMDescription(
        "Advance from the FF1 title screen through NEW GAME / class select / name entry into " +
            "the overworld. Mashes START then A. Termination: char1_hpLow != 0 OR worldX != 0. " +
            "Valid ONLY from pre-game phases (Boot/TitleOrMenu/NewGameMenu/NameEntry); not a " +
            "panic reset from Overworld/Indoors/Battle — guard rejects with REJECTED."
    )
    suspend fun pressStartUntilOverworld(maxAttempts: Int = 60): SkillResult {
        toolCallLog.append("pressStartUntilOverworld", "maxAttempts=$maxAttempts")
        // V5.31 panic-reset guard. iter14 evidence: agent called this from Indoors
        // as a panic move, mashing START+A through the in-game menu and wiping the
        // run. Reject unless we are still pre-party-creation (same termination
        // markers the skill itself uses, inverted).
        val ram = toolset.getState().ram
        val alreadyInGame = (ram["char1_hpLow"] ?: 0) != 0 || (ram["worldX"] ?: 0) != 0
        if (alreadyInGame) {
            return SkillResult(
                ok = false,
                message = "REJECTED: party already created (worldX=0x${(ram["worldX"] ?: 0).toString(16)}, " +
                    "char1_hp=0x${(ram["char1_hpLow"] ?: 0).toString(16)}). pressStartUntilOverworld " +
                    "is only valid from the title/menu — do NOT use as a panic reset. Use " +
                    "exitInterior or exploreInteriorFrontier from Indoors.",
                framesElapsed = 0,
                ramAfter = ram,
            )
        }
        return pressStartSkill.invoke(mapOf("maxAttempts" to "$maxAttempts"))
    }

    // V5.26: removed @Tool annotation. Per-step vision navigation is anti-pattern
    // (LLM driving directions). Method retained in case a future fallback needs
    // direct invocation, but it is no longer offered to the planner LLM.
    suspend fun walkInteriorVision(maxSteps: Int = 24): SkillResult {
        val skill = walkInteriorVisionSkill
            ?: return SkillResult(false,
                "vision navigator not configured (ANTHROPIC_API_KEY missing?)", 0, emptyMap())
        toolCallLog.append("walkInteriorVision", "maxSteps=$maxSteps")
        return skill.invoke(mapOf("maxSteps" to "$maxSteps"))
    }

    @Tool
    @LLMDescription(
        "Walk to the nearest interior exit using the offline ROM-decoder pathfinder. " +
            "Reliable on castles/dungeons; ~13% step success on town overlays — if " +
            "exitInterior fails twice in the same Indoors phase, switch to " +
            "exploreInteriorFrontier instead. Stops on sub-map transition, encounter, " +
            "or arrival on overworld."
    )
    suspend fun exitInterior(maxSteps: Int = 64): SkillResult {
        toolCallLog.append("exitInterior", "maxSteps=$maxSteps")
        return exitInteriorSkill.invoke(mapOf("maxSteps" to "$maxSteps"))
    }

    @Tool
    @LLMDescription(
        "(V5.29) Deterministic interior explorer. Walks the party tile-by-tile " +
            "toward the nearest UNVISITED reachable tile in the current FF1 " +
            "interior map, persisting visited tiles in InteriorMemory. Use this " +
            "when exitInterior fails on a town overlay — full map coverage exposes " +
            "exits as side effects. Stops on phase=Overworld, encounter, " +
            "fully-explored map, or repeated blocked direction. maxSteps default 64."
    )
    suspend fun exploreInteriorFrontier(maxSteps: Int = 64): SkillResult {
        toolCallLog.append("exploreInteriorFrontier", "maxSteps=$maxSteps")
        return exploreInteriorFrontierSkill.invoke(mapOf("maxSteps" to "$maxSteps"))
    }

    @Tool
    @LLMDescription(
        "(DEPRECATED) Query the offline interior pathfinder for the nearest exit. " +
            "Decoder is unreliable on town maps — prefer walkInteriorVision."
    )
    fun findPathToExit(): String {
        toolCallLog.appendNoArgs("findPathToExit")
        val ram = toolset.getState().ram
        val mapId = ram["currentMapId"] ?: -1
        if (mapId < 0) return "BLOCKED. currentMapId unknown."
        mapSession.ensureCurrent(mapId)
        // V2.6.4: localX/localY = scroll offset; party tile = scroll + (8, 7).
        val from = ((ram["localX"] ?: 0) + 8) to ((ram["localY"] ?: 0) + 7)
        val viewport = mapSession.readFullMapView(from)  // V2.6.2: full 64×64 BFS
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

    // V5.26: removed @Tool annotation. See walkInteriorVision for rationale.
    suspend fun walkOverworldVision(targetX: Int, targetY: Int, maxSteps: Int = 24): SkillResult {
        val skill = walkOverworldVisionSkill
            ?: return SkillResult(false,
                "vision overworld navigator not configured (ANTHROPIC_API_KEY missing?)", 0, emptyMap())
        toolCallLog.append("walkOverworldVision",
            "targetX=$targetX, targetY=$targetY, maxSteps=$maxSteps")
        return skill.invoke(mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps"))
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
        // V5.14: surface closestReachable + targetPassable so the agent knows
        // *why* a full path isn't available and where to aim instead.
        val tail = buildString {
            res.closestReachable?.let { append(" closestReachable=(${it.first},${it.second})") }
            if (res.targetPassable == false) append(" targetPassable=false")
        }
        return when {
            res.found && !res.partial ->
                "PATH ${res.steps.size} steps: ${res.steps.joinToString(",") { it.name }}$tail"
            res.found && res.partial ->
                "PARTIAL ${res.steps.size} steps to (${res.reachedTile.first},${res.reachedTile.second}); " +
                    "first steps: ${res.steps.take(8).joinToString(",") { it.name }}$tail"
            else -> "BLOCKED. ${res.reason ?: "no path"}.$tail Suggest askAdvisor or pick a reachable target."
        }
    }

    @Tool
    @LLMDescription("Run the registered FF1 battle_fight_all action.")
    suspend fun battleFightAll(): ActionToolResult {
        toolCallLog.appendNoArgs("battleFightAll")
        return toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
    }

    // V5.26: removed @Tool. Random-walk-until-encounter ignores fog blocks and
    // session memory, which iter8 evidence showed leads the agent into known
    // warp tiles even after pre-seeded warp memory was loaded. The legitimate
    // grinding use case is rare and can be re-introduced later as
    // grindEncounters(targetXp) with proper safe-tile gating.
    suspend fun walkUntilEncounter(): ActionToolResult {
        toolCallLog.appendNoArgs("walkUntilEncounter")
        return toolset.executeAction(profileId = "ff1", actionId = "walk_until_encounter")
    }

    @Tool
    @LLMDescription("Return frame count, watched RAM, CPU regs, held buttons.")
    fun getState(): StateSnapshot = toolset.getState()

    @Tool
    @LLMDescription(
        "Rest the party at the Coneria inn. Pre-condition: party must already be inside " +
            "the inn interior (currentMapId == innInteriorMapId). Taps A up to 30 times " +
            "until gold drops AND minHp% reaches 100 (Rested), or returns InnNotFound " +
            "if no heal observed. Use after walkInteriorVision navigates into the inn."
    )
    suspend fun restAtInn(innInteriorMapId: String): String {
        toolCallLog.append("restAtInn", "innInteriorMapId=$innInteriorMapId")
        val skill = RestAtInn(toolset)
        val result = skill.invoke(mapOf("innInteriorMapId" to innInteriorMapId))
        return result.message
    }

    @Tool
    @LLMDescription(
        "Probe the current building for inn behavior. Pre-condition: party must be inside " +
            "a candidate building (currentMapId != 0). Taps A up to 30 times watching for " +
            "gold drop + HP=100. On success, persists an NPC_INNKEEPER landmark and returns " +
            "Rested. On failure, returns WrongBuilding — caller should exitInterior and try " +
            "the next candidate building. Use during discovery mode when innkeeper is not cached."
    )
    suspend fun discoverInn(): String {
        toolCallLog.appendNoArgs("discoverInn")
        val skill = DiscoverInn(toolset, landmarks)
        val result = skill.invoke(emptyMap())
        return result.message
    }

    private val exploreOverworldFrontierSkill =
        ExploreOverworldFrontier(toolset, overworldMap, fog, overworldPathfinder, toolCallLog)

    /**
     * Explorer-phase deterministic walk to (targetX, targetY). Uses SalienceStrategy
     * upstream to pick the target. NOT exposed via @Tool because the explorer phase
     * does not run an LLM tool surface — SingleRun calls this directly.
     */
    suspend fun exploreOverworldFrontier(
        targetX: Int, targetY: Int, maxSteps: Int = 32,
    ): SkillResult {
        toolCallLog.append("exploreOverworldFrontier",
            "targetX=$targetX, targetY=$targetY, maxSteps=$maxSteps")
        return exploreOverworldFrontierSkill.invoke(
            mapOf("targetX" to "$targetX", "targetY" to "$targetY", "maxSteps" to "$maxSteps")
        )
    }
}
