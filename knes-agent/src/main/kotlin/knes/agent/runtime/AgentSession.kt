package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.advisor.StrategyAdvice
import knes.agent.executor.ExecutorAgent
import knes.agent.explorer.HaikuConsult
import knes.agent.skills.BuyAtShop
import knes.agent.skills.EnterConeriaWeaponShop
import knes.agent.skills.EquipWeapon
import knes.agent.skills.ExitInterior
import knes.agent.skills.GrindLoop
import knes.agent.skills.InteriorScanner
import knes.agent.skills.WalkInteriorVision
import knes.agent.skills.WalkOverworldTo
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.FrameChangeDetector
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.perception.ScreenshotPolicy
import knes.agent.perception.VisionInteriorNavigator
import knes.agent.perception.ViewportSource
import knes.agent.tools.EmulatorToolset
import java.nio.file.Path

data class Budget(
    val maxSkillInvocations: Int = 80,
    val maxAdvisorCalls: Int = 30,
    val costCapUsd: Double = 3.0,
    val wallClockCapSeconds: Int = 900,
)

class AgentSession(
    private val toolset: EmulatorToolset,
    private val observer: RamObserver,
    private val executor: ExecutorAgent,
    private val advisor: AdvisorAgent,
    private val toolCallLog: ToolCallLog = ToolCallLog(),
    private val budget: Budget = Budget(),
    /**
     * V5.24: shared FogOfWar so the session can mark warp tiles blocked.
     * BFS pathfinder honors fog blocks → deterministic reroute around
     * known warps. Optional for backward compat with tests.
     */
    private val fog: FogOfWar? = null,
    /**
     * V5.25: persistent overworld warp memory across runs. Default loads
     * from ~/.knes/ff1-overworld-warps.json. Tests pass an in-memory file
     * to keep the host filesystem clean.
     */
    private val warpMemory: OverworldWarpMemory = OverworldWarpMemory(),
    /**
     * Phase 2: persistent landmark catalog populated by the explorer phase
     * (towns, castles, dungeons, NPCs). Injected into both advisor and
     * executor observations so the planner can route to known points of
     * interest without rediscovery. Default loads from ~/.knes/ff1-landmarks.json.
     */
    private val landmarkMemory: LandmarkMemory = LandmarkMemory(),
    /**
     * Spec 2 / Task 9: optional dependencies needed for the outfit boot phase
     * (buy + equip starter weapons). All four must be non-null for the phase
     * to run; otherwise it is silently skipped. Defaulted to null so existing
     * tests + e2e callers that don't yet wire vision/MapSession keep working.
     */
    private val outfitVision: HaikuConsult? = null,
    private val outfitNavigator: VisionInteriorNavigator? = null,
    private val outfitViewportSource: ViewportSource? = null,
    private val outfitMapSession: MapSession? = null,
    /**
     * Spec 3a: optional dependencies for the post-BRIDGE phase that walks to
     * the Temple of Fiends entrance. When both are non-null, BridgeTick is
     * constructed and BRIDGE decisions flip `bridgePhaseActive=true`.
     * When either is null, BRIDGE behaves as before (LLM executor takes over).
     */
    private val bridgeVision: HaikuConsult? = null,
    private val bridgeViewportSource: ViewportSource? = null,
    runDir: Path = Trace.newRunDir(),
    /**
     * Optional per-turn hook fired after each executor turn. Receives current
     * phase and RAM snapshot. Returning a non-null Outcome short-circuits the
     * loop and returns that outcome. Used by fixture-builder tests to stop
     * the agent the moment a target state is reached (e.g. mapId=8 entered)
     * without modifying the production goal of reaching Garland.
     */
    private val onTurnEnd: ((FfPhase, Map<String, Int>) -> Outcome?)? = null,
) {
    private val resolvedRunDir = runDir
    private val trace = Trace(resolvedRunDir)
    private val screenshotPolicy = ScreenshotPolicy()

    /** Strategy mode state — see spec §2 (one-way switch). */
    private var grindModeActive: Boolean = true
    /** Spec 3a: post-BRIDGE phase active flag. Mutually exclusive with grindModeActive. */
    private var bridgePhaseActive: Boolean = false
    private val recentDecisions: RecentDecisionsBuffer = RecentDecisionsBuffer()

    /** Set when REST is chosen; cleared when heal completes or fallback timer expires. */
    private var strategicPlan: String? = null
    /** Counts executor turns spent on the strategic plan (timeout safeguard). */
    private var strategicPlanTurns: Int = 0
    private val STRATEGIC_PLAN_MAX_TURNS = 12

    /**
     * Grind corridor anchor — captured from party's actual worldX/Y on the first
     * GRIND decision in this session, instead of a hardcoded value. Spawn drifts
     * across savestates / ROM versions (observed: 146,158 vs originally-planned
     * 157,158); hardcoded anchor immediately fired WanderedOff and burned the
     * strategic budget without ever entering a fight.
     */
    private var grindAnchor: Pair<Int, Int>? = null
    /** Adaptive anchor: count consecutive Blocked/NoEncounter to detect dead-zone. */
    private var grindNoProgress: Int = 0
    /** Number of times the anchor has been shifted; cap to avoid runaway drift. */
    private var grindAnchorShifts: Int = 0
    private val GRIND_NOPROGRESS_THRESHOLD = 3
    private val GRIND_MAX_ANCHOR_SHIFTS = 5
    /** Pre-bridge target — separate waypoint, distinct from grind anchor. */
    private val BRIDGE_TILE: Pair<Int, Int> = 157 to 141
    private val TARGET_MIN_LEVEL: Int = 3

    /** Spec 3a: BridgeTick — null if vision deps absent. */
    private val bridgeTick: BridgeTick? =
        if (bridgeVision != null && bridgeViewportSource != null && fog != null) {
            BridgeTick(
                discover = {
                    knes.agent.skills.DiscoverChaosShrine(
                        toolset, bridgeViewportSource, landmarkMemory, bridgeVision,
                    ).invoke(emptyMap())
                },
                walk = { tx, ty ->
                    knes.agent.skills.WalkOverworldTo(
                        toolset, bridgeViewportSource, fog,
                    ).invoke(mapOf("targetX" to tx.toString(), "targetY" to ty.toString()))
                },
                landmarks = landmarkMemory,
            )
        } else null

    /** Test accessor — reflects whether the BridgeTick was wired at session ctor. */
    internal val bridgeTickIsConstructed: Boolean get() = bridgeTick != null

    private sealed interface SkillInvocation {
        data object Grind : SkillInvocation
        data object Rest : SkillInvocation
    }

    private suspend fun runStrategicTick(phase: FfPhase, ram: Map<String, Int>): SkillInvocation? {
        // Coneria entry tile for inn-distance calc — fall back to overworld center if absent.
        val coneriaEntry = landmarkMemory.all()
            .firstOrNull { it.kind == knes.agent.perception.LandmarkKind.TOWN_ENTRY }
            ?.let { (it.worldX ?: 0) to (it.worldY ?: 0) }
            ?: (152 to 159)  // fallback: pre-known approx for Coneria spawn area
        val prompt = StrategyAdvice.buildPrompt(
            ram = ram, recent = recentDecisions,
            innTile = coneriaEntry, bridgeTile = BRIDGE_TILE, targetMinLevel = TARGET_MIN_LEVEL,
        )
        val raw = advisor.consultStrategy(prompt)
        val parsed = StrategicDecision.parse(raw) ?: StrategicDecision.GRIND
        val guarded = StrategyAdvice.applySanityGuards(parsed, ram, recentDecisions.isThrashing())
        recentDecisions.record(guarded)
        println("[strategy] raw='${raw.take(40)}' parsed=$parsed guarded=$guarded recent=${recentDecisions.snapshot()}")
        trace.record(TraceEvent(turn = 0, role = "strategy", phase = phase.toString(),
            input = prompt, output = "raw=$raw parsed=$parsed guarded=$guarded"))
        return when (guarded) {
            StrategicDecision.GRIND -> SkillInvocation.Grind
            StrategicDecision.REST -> SkillInvocation.Rest
            StrategicDecision.BRIDGE -> {
                grindModeActive = false
                if (bridgeTick != null) bridgePhaseActive = true
                null
            }
        }
    }

    /** One-shot guard for outfit boot phase — fires on first Overworld observation,
     *  not at session start (party RAM coords are still 0,0 at title screen). */
    private var outfitBootPhaseDone: Boolean = false

    suspend fun run(): Outcome {
        println("[knes-agent] run dir: $resolvedRunDir")
        var previousPhase: FfPhase? = null
        var currentPlan = "Start the game from the title screen and begin a new game."
        var idleTurns = 0
        var lastRam: Map<String, Int> = emptyMap()
        var advisorCalls = 0
        var skillsInvoked = 0
        val startMs = System.currentTimeMillis()
        // V5.23: cross-turn memory of FF1 ROM-encoded warp tiles the agent has tripped
        // into. Both AIAgent instances are spun up fresh each turn (singleRunStrategy +
        // newAgent per phase) so they have NO native memory of prior failures. We track
        // here and inject into both advisor and executor observations so neither suggests
        // routing back through a known warp.
        val failedWarpTiles: MutableSet<Pair<Int, Int>> = mutableSetOf()
        val failedRegex = FAILED_WARP_REGEX
        // V5.25: pre-seed from persistent memory so the very first walk in
        // this run already knows about warps detected in earlier sessions.
        // Both the LLM hint (text injection) and the deterministic fog block
        // are armed before the agent moves.
        val seededWarps = warpMemory.all()
        if (seededWarps.isNotEmpty()) {
            failedWarpTiles += seededWarps
            println("[overworld-warp-memory] preloaded ${seededWarps.size} known warps: $seededWarps")
            fog?.let { f ->
                // V5.28: 1x1 block per warp tile. 3x3 (V5.24) was too aggressive
                // — iter11 evidence: overlapping 3x3 zones around (145,152) and
                // (147,153) sealed the agent into a 1-tile pocket at (145,153).
                // Warps are confirmed 1x1 (each ROM-encoded entry tile is a
                // separate trigger); siblings get auto-detected if also warps.
                seededWarps.forEach { tile -> f.markBlocked(tile.first, tile.second) }
                println("[overworld-warp-memory] fog.markBlocked exact tiles only (1x1)")
            }
        }

        val landmarkContext: String? = LandmarkContext.render(landmarkMemory)
        if (landmarkContext != null) {
            println("[landmark-memory] preloaded ${landmarkMemory.all().size} landmarks (advisor + executor injection)")
        }

        // V5.34: postbattle auto-dismiss zombie-loop guard. If RAM gets stuck
        // in PostBattle/Battle screenState (e.g. enemy_dead flags clear but the
        // engine never transitions back to overworld), the auto-dismiss branch
        // below loops forever because `continue` skips the budget checks at
        // line 241-243. Track consecutive dismissals; bail when threshold hit.
        var consecutivePostBattle = 0
        // V5.34.4: bumped 60→150. attempt9 evidence: agent fought 51 battles
        // and hit the 60-dismiss cap exactly — bailing mid-progress. 51 battles
        // × 3-4 screens each (XP / level-up / gold) = ~200 dismiss cycles
        // possible. 150 gives headroom while still catching genuine zombie
        // loops (no progress within ~150 frames is clearly stuck).
        val POSTBATTLE_DISMISS_CAP = 150
        // V5.34.3: confirm UnknownMapTrap before bail. attempt8 + FF1 disasm
        // research showed (mapflags=1 + mapId=0) is NOT exclusively a trap:
        // it also fires as a 1-frame artifact during battle entry on tile 0x00
        // (FIGHT-normal random encounter). Require N consecutive observations
        // and require phase is NOT Battle/PostBattle (those resolve themselves).
        var consecutiveTrapObs = 0
        val TRAP_CONFIRM_THRESHOLD = 3

        try {
            while (true) {
                // V5.34: budget enforcement at TOP of loop, BEFORE phase
                // observation. Previously these checks lived only after the
                // phase-handling switch (line 241-243), so any branch that
                // `continue`d would bypass them. Moved here so wallClock /
                // skillCap always fire even when an inner branch loops.
                val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
                if (elapsedSec > budget.wallClockCapSeconds) return Outcome.OutOfBudget
                if (skillsInvoked > budget.maxSkillInvocations) return Outcome.OutOfBudget

                val phase = observer.observeWithVision()
                val ram = observer.ramSnapshot()

                val outcome = SuccessCriteria.evaluate(phase)
                if (outcome != Outcome.InProgress) {
                    trace.record(TraceEvent(0, "outcome", phase.toString(), note = outcome.name))
                    return outcome
                }

                // Spec 2: outfit boot phase — fires on FIRST Overworld observation
                // (party RAM coords are 0,0 at title screen, so running this at
                // session start would always fail walk-to-coneria). Best-effort:
                // any failure logs and falls through to strategic loop.
                if (!outfitBootPhaseDone && phase is FfPhase.Overworld) {
                    outfitBootPhaseDone = true
                    try {
                        runOutfitBootPhase()
                    } catch (e: Exception) {
                        println("[boot_outfit] uncaught: ${e.message}")
                        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                            note = "boot_outfit_summary failed: ${e.message}"))
                    }
                }

                // V2.5.6: deterministic PostBattle dismissal. The modal blocks input;
                // until it clears, walkOverworldTo / exitInterior return BLOCKED. The LLM
                // sometimes loops on these instead of calling battleFightAll, burning the
                // budget. Auto-tap A here so the agent never waits on the modal.
                // V5.34.2/3: detect UnknownMapTrap (mapId=0 + mapflags=1)
                // with persistence guard. attempt7 evidence + FF1 disasm
                // research: tile 0x00 has FIGHT-normal flag (random encounter,
                // NOT teleport). Battle entry produces a 1-frame artifact
                // where mapflags=1 + mapId=0 BEFORE screenState transitions
                // to 0x68 (Battle). Require:
                //   (a) 3 consecutive observations of trap state
                //   (b) phase is NOT Battle/PostBattle (those self-resolve)
                // Only then declare trap, persist warp memory, and bail.
                val mapflagsBit = (ram["mapflags"] ?: 0) and 0x01
                val ramMapIdNow = ram["currentMapId"] ?: -1
                val isInTrapState = mapflagsBit == 1 && ramMapIdNow == 0 &&
                    phase !is FfPhase.Battle && phase !is FfPhase.PostBattle
                if (isInTrapState) {
                    consecutiveTrapObs++
                    if (consecutiveTrapObs >= TRAP_CONFIRM_THRESHOLD) {
                        val trapX = ram["worldX"] ?: -1
                        val trapY = ram["worldY"] ?: -1
                        if (trapX in 0..255 && trapY in 0..255 &&
                            (trapX to trapY) !in failedWarpTiles) {
                            failedWarpTiles += trapX to trapY
                            fog?.markBlocked(trapX, trapY)
                            warpMemory.record(trapX, trapY, mapId = 0,
                                note = "UnknownMapTrap — confirmed after $consecutiveTrapObs consecutive observations")
                            warpMemory.save()
                            println("[unknown-map-trap] persisted warp block at ($trapX,$trapY) after $consecutiveTrapObs obs")
                        }
                        trace.record(TraceEvent(0, "outcome", phase.toString(),
                            note = "UnknownMapTrap at world($trapX,$trapY) — confirmed after $consecutiveTrapObs obs, bailing"))
                        return Outcome.OutOfBudget
                    }
                } else {
                    consecutiveTrapObs = 0
                }

                if (phase is FfPhase.PostBattle) {
                    consecutivePostBattle++
                    if (consecutivePostBattle > POSTBATTLE_DISMISS_CAP) {
                        // V5.34: dismiss is not transitioning out of PostBattle —
                        // engine is stuck (RAM enemy_dead flags clear but
                        // screenState frozen). Bail rather than spam forever.
                        trace.record(TraceEvent(0, "outcome", phase.toString(),
                            note = "PostBattle dismiss zombie loop after $consecutivePostBattle attempts"))
                        return Outcome.OutOfBudget
                    }
                    println("[postbattle auto-dismiss] ($consecutivePostBattle/$POSTBATTLE_DISMISS_CAP)")
                    toolset.executeAction(profileId = "ff1", actionId = "battle_fight_all")
                    trace.record(TraceEvent(
                        turn = 0, role = "system", phase = phase.toString(),
                        note = "auto-dismissed PostBattle via battle_fight_all",
                    ))
                    continue  // re-observe phase next iteration
                }
                consecutivePostBattle = 0  // reset when phase leaves PostBattle

                // V5.35/V5.36: strategic decision gate. Guard: do not fire while a
                // strategic plan is active (REST heal cycle in progress).
                // See spec §2 + §9.
                if (grindModeActive && phase is FfPhase.Overworld && strategicPlan == null) {
                    val invocation = runStrategicTick(phase, ram)
                    when (invocation) {
                        SkillInvocation.Grind -> {
                            if (grindAnchor == null) {
                                val wx = ram["worldX"] ?: 0
                                val wy = ram["worldY"] ?: 0
                                grindAnchor = wx to wy
                                println("[strategy:grind] captured anchor=($wx,$wy) on first GRIND")
                            }
                            val (ax, ay) = grindAnchor!!
                            // V5.36.1: corridor radius 3→6 + max steps 6→12.
                            // Validation run 2 evidence: spawn at (146,158) sits in
                            // a no-encounter pocket near Coneria castle; with the
                            // default 3-tile radius the party oscillated y=157-159
                            // for 50+ grind cycles without ever triggering a battle.
                            // Larger corridor reaches into the encounter-bearing
                            // grass to the north of the safe zone.
                            val res = GrindLoop(toolset).invoke(mapOf(
                                "anchorX" to ax.toString(),
                                "anchorY" to ay.toString(),
                                "corridorRadius" to "6",
                                "maxStepsWithoutEncounter" to "12",
                            ))
                            println("[strategy:grind] ok=${res.ok} ${res.message.take(120)}")
                            // V5.36.2: adaptive anchor. Validation run 3 evidence:
                            // spawn area (146,158) + corridor radius 6 still landed
                            // entirely in the Coneria peninsula no-encounter pocket.
                            // After N consecutive non-progress results (NoEncounter
                            // or Blocked), shift anchor (+2 E, -4 N) toward the
                            // bridge — that's the empirically encounter-rich path
                            // per prior session memory. Cap shifts so we don't
                            // walk off the map.
                            val msg = res.message
                            val noProgress = msg.startsWith("NoEncounter") || msg.startsWith("Blocked")
                            if (noProgress) {
                                grindNoProgress++
                                if (grindNoProgress >= GRIND_NOPROGRESS_THRESHOLD &&
                                    grindAnchorShifts < GRIND_MAX_ANCHOR_SHIFTS) {
                                    val (oax, oay) = grindAnchor!!
                                    val nax = oax + 2
                                    val nay = oay - 4
                                    grindAnchor = nax to nay
                                    grindAnchorShifts++
                                    grindNoProgress = 0
                                    println("[strategy:grind] adaptive shift #${grindAnchorShifts}: " +
                                        "anchor ($oax,$oay) -> ($nax,$nay)")
                                }
                            } else {
                                grindNoProgress = 0
                            }
                            continue
                        }
                        SkillInvocation.Rest -> {
                            val cached = landmarkMemory.findInnkeeper()
                            val coneriaEntry = landmarkMemory.all()
                                .firstOrNull { it.kind == knes.agent.perception.LandmarkKind.TOWN_ENTRY }
                            val coneriaCoords = coneriaEntry?.let { (it.worldX ?: 152) to (it.worldY ?: 159) }
                                ?: (152 to 159)

                            strategicPlan = if (cached != null && cached.mapId != null) {
                                """
                                REST CYCLE — known innkeeper landmark.
                                Goal: heal the party at the Coneria inn, then resume.
                                Sub-steps:
                                  1. walkOverworldTo target=(${coneriaCoords.first},${coneriaCoords.second}) — Coneria town entry on the overworld.
                                  2. After entering Coneria town interior, walkInteriorVision toward the inn building (mapId=${cached.mapId}, innkeeper at local=(${cached.localX},${cached.localY})).
                                  3. Once inside the inn (currentMapId == ${cached.mapId}), call rest_at_inn with innInteriorMapId=${cached.mapId}.
                                  4. After Rested, exit and return to grind area.
                                Budget: complete within ${STRATEGIC_PLAN_MAX_TURNS} executor turns.
                                """.trimIndent()
                            } else {
                                """
                                REST CYCLE — discovery mode (no innkeeper landmark cached).
                                Goal: find the Coneria inn, heal the party, persist the landmark for future runs.
                                Sub-steps:
                                  1. walkOverworldTo target=(${coneriaCoords.first},${coneriaCoords.second}) — Coneria town entry.
                                  2. Once inside Coneria town interior, exploreInteriorFrontier or walkInteriorVision to enter candidate buildings.
                                  3. Each time you enter a sub-building (currentMapId changes to a new value), call discover_inn (no args).
                                     - If it returns Rested, the inn is found and persisted; exit and resume grind.
                                     - If it returns WrongBuilding, exit (exitInterior) and try the next building.
                                  4. After ${STRATEGIC_PLAN_MAX_TURNS} turns without success, give up and let strategic-tick reconsider.
                                """.trimIndent()
                            }
                            strategicPlanTurns = 0
                            println("[strategy:rest] plan injected (cache=${if (cached != null) "HIT mapId=${cached.mapId}" else "MISS"})")
                            // Fall through to existing advisor/executor flow — no `continue`.
                        }
                        null -> { /* BRIDGE: grindModeActive flipped, fall through */ }
                    }
                }

                if (bridgePhaseActive && phase is FfPhase.Overworld && strategicPlan == null) {
                    val r = bridgeTick!!.run(ram)
                    when (r) {
                        is BridgeTick.TickOutcome.Reached -> {
                            bridgePhaseActive = false
                            val wxNow = ram["worldX"] ?: -1
                            val wyNow = ram["worldY"] ?: -1
                            println("[bridge_phase] reached at ($wxNow,$wyNow)")
                            trace.record(TraceEvent(turn = 0, role = "system", phase = phase.toString(),
                                note = "bridge_phase_summary: reached at ($wxNow,$wyNow)"))
                            continue
                        }
                        is BridgeTick.TickOutcome.BailToLlm -> {
                            bridgePhaseActive = false
                            println("[bridge_phase] bailed_to_llm")
                            trace.record(TraceEvent(turn = 0, role = "system", phase = phase.toString(),
                                note = "bridge_phase_summary: bailed_to_llm"))
                            // fall through to LLM executor
                        }
                        is BridgeTick.TickOutcome.Continue -> continue
                    }
                }

                // V5.36: strategic plan override. When REST has injected a plan, force it
                // into currentPlan and skip the regular advisor consult. Increment turn
                // counter; clear the plan after success (HP=100%) or timeout.
                if (strategicPlan != null) {
                    val minHpPct = knes.agent.runtime.StrategyContext.minHpPct(ram)
                    if (minHpPct == 100 && strategicPlanTurns >= 2) {
                        // Heal complete — return to grind.
                        println("[strategy:rest] heal complete (minHp%=100) after $strategicPlanTurns turns; clearing plan")
                        strategicPlan = null
                        strategicPlanTurns = 0
                    } else if (strategicPlanTurns >= STRATEGIC_PLAN_MAX_TURNS) {
                        println("[strategy:rest] timeout after $strategicPlanTurns turns; clearing plan, resuming strategic tick")
                        strategicPlan = null
                        strategicPlanTurns = 0
                    } else {
                        currentPlan = strategicPlan!!
                        strategicPlanTurns++
                        println("[strategy:rest] injecting plan (turn $strategicPlanTurns/$STRATEGIC_PLAN_MAX_TURNS)")
                    }
                }

                val phaseChanged = previousPhase == null || previousPhase::class != phase::class
                // V4 hybrid C: advisor consult earlier when stuck inside an interior.
                // The decoder is unreliable on towns; vision-by-advisor (not per-step
                // skill) gives cardinal hints without burning vision tokens every step.
                val stuckInInterior = phase is FfPhase.Indoors && idleTurns >= 5
                if (phaseChanged || stuckInInterior || idleTurns >= 20) {
                    if (++advisorCalls > budget.maxAdvisorCalls) return Outcome.OutOfBudget
                    val attachShot = screenshotPolicy.shouldAttach(previousPhase, phase)
                    val reason = when {
                        phaseChanged -> "phase change"
                        stuckInInterior -> "stuck in interior (idle=$idleTurns) — please look at the screen and suggest a cardinal direction"
                        else -> "watchdog stuck (idle=$idleTurns)"
                    }
                    val obs = buildString {
                        append("Phase: $phase\nRAM: $ram\n")
                        if (attachShot) append("(screenshot available via getScreen)\n")
                        if (failedWarpTiles.isNotEmpty()) {
                            // V5.23: session memory injected so the planner reroutes
                            // around tiles that previously warped the party indoors.
                            append("Session memory — known FF1 warp tiles to avoid as " +
                                "targets or route-throughs: ")
                            append(failedWarpTiles.joinToString(", ") { "(${it.first},${it.second})" })
                            append('\n')
                        }
                        if (landmarkContext != null) {
                            append(landmarkContext)
                            append('\n')
                        }
                        append("Reason: $reason")
                    }
                    println("[advisor #$advisorCalls] phase=$phase")
                    currentPlan = advisor.plan(phase, obs)
                    println("[advisor plan] ${currentPlan.lineSequence().take(3).joinToString(" | ").take(200)}")
                    trace.record(
                        TraceEvent(
                            turn = 0, role = "advisor", phase = phase.toString(),
                            input = obs,           // full observation given to advisor
                            output = currentPlan,  // full advisor reasoning, untruncated
                        )
                    )
                    idleTurns = 0
                }

                val executorInput = buildString {
                    append("Plan:\n$currentPlan\n\nCurrent phase: $phase\nRAM: $ram")
                    if (failedWarpTiles.isNotEmpty()) {
                        append("\nSession memory — known FF1 warp tiles to avoid as targets " +
                            "or route-throughs: ")
                        append(failedWarpTiles.joinToString(", ") { "(${it.first},${it.second})" })
                    }
                    if (landmarkContext != null) {
                        append('\n')
                        append(landmarkContext)
                    }
                }
                println("[executor turn=$skillsInvoked] phase=$phase idle=$idleTurns")
                val result = executor.run(phase, executorInput)
                skillsInvoked += 1
                println("[executor result] ${result.lineSequence().take(2).joinToString(" | ").take(160)}")
                val drainedCalls = toolCallLog.drain()
                println("[executor calls] ${drainedCalls.joinToString(" ; ").take(200)}")
                // V5.23: extract UNEXPECTED warp tiles from the toolCallLog (set by
                // WalkOverworldTo.aborted when targeted=false). Drained calls include
                // the abort message verbatim, so this is a stable signal independent
                // of how the LLM phrases its final response.
                drainedCalls.forEach { call ->
                    failedRegex.findAll(call).forEach { m ->
                        val tile = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                        val transitionMapId = m.groupValues[3].toInt()
                        // mapId=0 + mapflags=1 is UnknownMapTrap (engine void), not a real
                        // interior. Recording it as a warp would dead-end priority-0 targeting.
                        if (transitionMapId == 0) {
                            println("[session-memory] skip warp record at $tile (mapId=0 trap, not real interior)")
                            return@forEach
                        }
                        if (failedWarpTiles.add(tile)) {
                            println("[session-memory] +failedWarpTile=$tile (total=${failedWarpTiles.size})")
                            // V5.28: 1x1 block. Was 3x3 in V5.24 on the assumption
                            // FF1 warps could span 2x1, but iter10/iter11 evidence
                            // says each entry tile is a separate trigger. 3x3
                            // overlap sealed a 1-tile pocket at (145,153). Sibling
                            // warps auto-detected on subsequent steps.
                            fog?.let { f ->
                                f.markBlocked(tile.first, tile.second)
                                println("[session-memory] +fog.markBlocked exact $tile")
                            }
                            // V5.25: persist for future sessions. Save immediately
                            // so a crash mid-run doesn't lose the discovery.
                            warpMemory.record(tile.first, tile.second,
                                mapId = transitionMapId,
                                note = "auto-detected ${java.time.Instant.now()}")
                            warpMemory.save()
                        }
                    }
                }
                trace.record(
                    TraceEvent(
                        turn = 0, role = "executor", phase = phase.toString(),
                        input = executorInput,   // full prompt sent to executor (with current plan + RAM)
                        output = result,         // full executor reasoning + final response, untruncated
                        toolCalls = drainedCalls,
                    )
                )

                val newRam = observer.ramSnapshot()
                idleTurns = if (newRam == lastRam) idleTurns + 1 else 0
                lastRam = newRam
                previousPhase = phase

                onTurnEnd?.invoke(phase, newRam)?.let { hookOutcome ->
                    trace.record(TraceEvent(turn = 0, role = "hook", phase = phase.toString(),
                        note = "onTurnEnd → $hookOutcome"))
                    return hookOutcome
                }

                // V5.34: top-of-loop budget enforcement covers all cases now.
            }
        } finally {
            trace.close()
        }
    }

    /**
     * Spec 2 / Task 9: outfit boot phase orchestrator. Runs once at session
     * start before the strategic loop. Steps:
     *   1. Entry guards via OutfitBootPhase: skip if RAM already shows all 4
     *      chars equipped.
     *   2. Walk to Coneria town entry.
     *   3. Use cached weapon shop landmark, or probe candidates with DiscoverShop.
     *   4. Per-character buy loop (BuyAtShop), retrying on WrongClass.
     *   5. Exit shop interior, then per-character equip loop (EquipWeapon)
     *      on the overworld.
     *   6. Log boot_outfit_summary.
     *
     * Skip semantics are gameplay-derived (RAM weapon-equipped flags). No
     * savestate-hash-keyed flag file: on resume, the RAM check itself short-
     * circuits the phase if the work is already done.
     *
     * Best-effort: any failure logs and falls through to the strategic loop
     * without bailing. Silently no-ops when optional vision deps are absent.
     */
    private suspend fun runOutfitBootPhase() {
        val phase = OutfitBootPhase(
            toolset = toolset,
            landmarks = landmarkMemory,
            trace = { kind, msg ->
                println("[boot_outfit] $kind: $msg")
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "$kind: $msg"))
            }
        )
        val pre = phase.run()
        if (pre.skipped || pre.reason == "no_town_entry") return

        // Full orchestration requires vision + mapSession + viewportSource (all optional
        // constructor params — fall through if any missing so existing tests keep working).
        if (outfitVision == null || outfitNavigator == null ||
            outfitViewportSource == null || outfitMapSession == null || fog == null) {
            println("[boot_outfit] dependencies_unavailable — skipping full orchestration")
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_outfit_summary: dependencies_unavailable"))
            return
        }

        val coneriaEntry = pre.coneriaEntry
            ?: run {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_outfit_summary: internal_error_no_coneria_entry_after_guard"))
                return
            }

        // 2. Walk to Coneria
        val walkResult = WalkOverworldTo(toolset, outfitViewportSource, fog).invoke(mapOf(
            "targetX" to (coneriaEntry.worldX ?: 0).toString(),
            "targetY" to (coneriaEntry.worldY ?: 0).toString(),
        ))
        if (!walkResult.ok) {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_outfit_summary: walk_to_coneria_failed: ${walkResult.message}"))
            return
        }

        // 2b. Settle warp transition. WalkOverworldTo terminates the moment
        //     party RAM coords match the destination, but the FF1 engine's
        //     warp tile triggers a screen-fade + map-load that takes ~30
        //     frames to update currentMapId. Without this, step 3's
        //     WalkInteriorVision sees mapId=0 and returns NotInBuilding,
        //     killing the boot phase before it can find the shop.
        //     If after settling we're still on overworld (mapId=0), nudge
        //     into the entry tile by stepping toward it from each cardinal.
        toolset.step(buttons = emptyList(), frames = 30)
        var postWalkMapId = toolset.getState().ram["currentMapId"] ?: 0
        if (postWalkMapId == 0) {
            for (dir in listOf("UP", "DOWN", "LEFT", "RIGHT")) {
                toolset.tap(dir, count = 1, pressFrames = 24, gapFrames = 12)
                toolset.step(buttons = emptyList(), frames = 30)
                postWalkMapId = toolset.getState().ram["currentMapId"] ?: 0
                if (postWalkMapId != 0) break
            }
        }
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_walk_settle: postWalkMapId=$postWalkMapId"))

        // 3. Cached weapon shop or probe via DiscoverShop on candidate NPC_SHOPKEEPERs.
        val cachedShop = landmarkMemory.findByKind(LandmarkKind.NPC_SHOPKEEPER)
            .firstOrNull { it.note.contains("kind=weapon") }
        val activeShop = cachedShop ?: discoverWeaponShop(outfitVision, outfitNavigator,
            outfitMapSession, fog)
        if (activeShop == null) {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_outfit_summary: boot_shop_not_found"))
            return
        }

        // 3b. Spec 5: navigate from town overlay (mapId=8) into the weapon shop
        //     sub-interior so BuyAtShop's `landmark.mapId == currentMapId` precondition
        //     holds. Skipped if party already inside a sub-shop (mapId != 8) — e.g.
        //     warm-start where an earlier run left the party in the shop.
        val currentMapId = toolset.getState().ram["currentMapId"] ?: 0
        if (currentMapId == 8) {
            val enterSkill = EnterConeriaWeaponShop(toolset, landmarkMemory)
            val r = enterSkill.invoke(emptyMap())
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_enter_shop: ${r.message}"))
            if (!r.ok) {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_outfit_summary: enter_shop_failed"))
                return
            }
        }

        // 4. Per-char buy loop.
        val buySkill = BuyAtShop(toolset, landmarkMemory)
        val charsBought = mutableListOf<Int>()
        val initialGold = StrategyContext.totalGold(toolset.getState().ram)
        // Default mapping: each char gets one of the 4 first inventory slots.
        val weaponSlotByChar = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3)
        for (charSlot in 1..4) {
            if (StrategyContext.anyWeaponEquipped(toolset.getState().ram, charSlot)) continue
            var slot = weaponSlotByChar[charSlot] ?: continue
            var retries = 0
            while (retries < 3) {
                val r = buySkill.invoke(mapOf(
                    "itemSlot" to slot.toString(),
                    "forCharSlot" to charSlot.toString(),
                    "expectedKeeperKind" to "weapon",
                ))
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_purchase: char$charSlot slot=$slot result=${r.message}"))
                if (r.ok) { charsBought += charSlot; break }
                if (r.message.contains("WrongClass")) {
                    slot = (slot + 1) % 4
                    retries++
                    continue
                }
                break
            }
        }

        // 5. Exit shop interior; equip on overworld.
        ExitInterior(toolset, outfitMapSession, fog).invoke(emptyMap())

        val equipSkill = EquipWeapon(toolset)
        val charsEquipped = mutableListOf<Int>()
        for (charSlot in 1..4) {
            val ram = toolset.getState().ram
            val ownedSlot = (0..3).firstOrNull {
                StrategyContext.weaponId(StrategyContext.weaponSlot(ram, charSlot, it)) != 0
            } ?: continue
            if (StrategyContext.isEquipped(StrategyContext.weaponSlot(ram, charSlot, ownedSlot))) {
                charsEquipped += charSlot
                continue
            }
            val r = equipSkill.invoke(mapOf(
                "charSlot" to charSlot.toString(),
                "weaponSlot" to ownedSlot.toString(),
            ))
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_equip: char$charSlot slot=$ownedSlot result=${r.message}"))
            if (r.ok) charsEquipped += charSlot
        }

        // 6. Summary.
        val finalGold = StrategyContext.totalGold(toolset.getState().ram)
        val goldSpent = (initialGold - finalGold).coerceAtLeast(0)
        val summary = "candidatesProbed=${if (cachedShop == null) 1 else 0} " +
            "weaponShopFound=true weaponsBought=${charsBought.size} " +
            "weaponsEquipped=${charsEquipped.size} totalGoldSpent=$goldSpent"
        println("[boot_outfit] boot_outfit_summary: $summary")
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_outfit_summary: $summary"))
    }

    private suspend fun discoverWeaponShop(
        vision: HaikuConsult,
        navigator: VisionInteriorNavigator,
        mapSession: MapSession,
        fog: FogOfWar,
    ): Landmark? {
        // Spec 4 (2026-05-08): brittle DiscoverShop probe loop replaced with
        // goal-aware InteriorExplorer (two-pass scanner + frame-change-gated
        // walking). WalkInteriorVision is unchanged per autonomy_principle.md.
        // See docs/superpowers/specs/2026-05-08-ff1-interior-self-discovery-design.md.
        // `navigator`, `mapSession`, `fog` are retained on the signature for
        // source compatibility; navigator is consumed by the WalkInteriorVision
        // helper, the others are currently unused (cleanup in a follow-up).

        val walkSkill = buildWalkInteriorVision(navigator, mapSession)
        val runId = resolvedRunDir.fileName?.toString() ?: "run"
        val sink: InteriorTraceSink = { note ->
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT", note = note))
        }
        val explorer = InteriorExplorer(
            walk = RealWalkInteriorVisionAdapter(walkSkill, toolset),
            scanner = InteriorScanner(vision, landmarkMemory, runId, traceSink = sink),
            frameDetector = FrameChangeDetector(),
            emu = RealInteriorEmulatorState(toolset),
            memory = landmarkMemory,
            traceSink = sink,
        )

        val outcome = explorer.exploreUntilFound(
            goal = LandmarkKind.NPC_SHOPKEEPER,
            predicate = { it.note.contains("kind=weapon") },
            capSteps = 200,
        )

        return when (outcome) {
            is InteriorExplorer.ExploreOutcome.Found -> {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "interior_explore_outcome: Found, " +
                        "scans=${outcome.stats.scansTriggered}, " +
                        "confirmed=${outcome.stats.confirmed}, " +
                        "walkSteps=${outcome.stats.walkSteps}, " +
                        "costUsd=${outcome.stats.costUsd}"))
                outcome.landmark
            }
            is InteriorExplorer.ExploreOutcome.NotFoundCapReached -> {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "interior_explore_outcome: NotFoundCapReached, " +
                        "scans=${outcome.stats.scansTriggered}, " +
                        "candidates=${outcome.stats.candidatesEvaluated}, " +
                        "walkSteps=${outcome.stats.walkSteps}, " +
                        "costUsd=${outcome.stats.costUsd}"))
                null
            }
            is InteriorExplorer.ExploreOutcome.StuckBailout -> {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "interior_explore_outcome: StuckBailout reason=${outcome.reason}, " +
                        "walkSteps=${outcome.stats.walkSteps}, costUsd=${outcome.stats.costUsd}"))
                null
            }
            is InteriorExplorer.ExploreOutcome.EncounterTriggered -> {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "interior_explore_outcome: EncounterTriggered, " +
                        "walkSteps=${outcome.stats.walkSteps}"))
                null
            }
        }
    }

    private fun buildWalkInteriorVision(
        navigator: VisionInteriorNavigator,
        mapSession: MapSession,
    ): WalkInteriorVision {
        return WalkInteriorVision(toolset, navigator, mapSession = mapSession)
    }

    companion object {
        /** Matches `WalkOverworldTo`'s `aborted` toolCallLog entry:
         *  `"entered interior after N steps; world=(X,Y) mapId=M party=(...) targeted=false"`.
         *  Capture groups: 1=worldX, 2=worldY, 3=mapId.
         *
         *  The mapId capture lets the auto-detect filter out UnknownMapTrap (mapId=0)
         *  transitions — they don't lead to a real interior, just engine void state, and
         *  recording them as warps would dead-end priority-0 warp targeting next campaign. */
        val FAILED_WARP_REGEX = Regex("""world=\((\d+),(\d+)\) mapId=(-?\d+)[^|]*targeted=false""")
    }
}
