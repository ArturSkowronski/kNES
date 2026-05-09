package knes.agent.runtime

import knes.agent.advisor.AdvisorAgent
import knes.agent.advisor.StrategyAdvice
import knes.agent.executor.ExecutorAgent
import knes.agent.explorer.HaikuConsult
import knes.agent.skills.BuyAtShop
import knes.agent.skills.EquipWeapon
import knes.agent.skills.ExitInterior
import knes.agent.skills.GrindLoop
import knes.agent.skills.SkillResult
import knes.agent.skills.WalkOverworldTo
import knes.agent.perception.FfPhase
import knes.agent.perception.FogOfWar
import knes.agent.perception.Landmark
import knes.agent.perception.LandmarkKind
import knes.agent.perception.LandmarkMemory
import knes.agent.perception.MapSession
import knes.agent.perception.OverworldWarpMemory
import knes.agent.perception.RamObserver
import knes.agent.perception.ScreenshotPolicy
import knes.agent.perception.ShopUiDetector
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
    private val outfitAdvisor: HaikuConsult? = null,
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
    /**
     * 2026-05-09 cont 3: persistent action notebook. Records significant agent
     * actions (boot walk-in, BuyAtShop iters, exit attempts) and serializes
     * alongside savestate dumps as `*.actions.json`. On savestate-load with a
     * sister file present, the loaded scratchpad is the agent's "memory of how
     * it got here" — usable for reverse-replay or LLM prompt context.
     * Defaults to a fresh per-session scratchpad when no prior history exists.
     */
    private val scratchpad: AgentScratchpad = AgentScratchpad.newSession(),
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

        // Deterministic pre-boot: drive past the title screen and run the outfit
        // boot phase BEFORE the LLM loop starts. Run #9 (2026-05-08) showed why
        // this matters — when the LLM combined `pressStartUntilOverworld` and
        // `walkOverworldTo(146, 150)` into a single turn 1 plan, the party
        // walked through a warp tile at world (146, 152) into Coneria CASTLE
        // (mapId=8) before the next turn boundary. The boot trigger never saw
        // an Overworld observation and missed firing entirely. Driving the
        // press-start + boot deterministically here removes that race.
        try {
            val preRam = toolset.getState().ram
            val titleScreen = (preRam["char1_str"] ?: 0) == 0
            if (titleScreen) {
                println("[knes-agent] pre-boot: pressing Start until overworld")
                knes.agent.skills.PressStartUntilOverworld(toolset).invoke(emptyMap())
            }
        } catch (e: Exception) {
            println("[knes-agent] pre-boot pressStart failed: ${e.message}")
        }
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
        // 2026-05-09 cont 3: bumped 3→10. Coneria south exit lands at world
        // (147,155) on a peninsula gate tile; the adjacent overworld tile
        // walks back into town (mapflags=1), looking like UnknownMapTrap.
        // GrindLoop has adaptive anchor-shift (3 NoEncounter → shift +2 E -4 N
        // toward bridge) but needs more breathing room than 3 obs to fire.
        val TRAP_CONFIRM_THRESHOLD = 10

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

                // Spec 2: outfit boot phase — fires once when the party is on the
                // overworld with an initialized party (post-pressStart). Trigger is
                // RAM-based, not FfPhase-based: in run #9 the LLM combined
                // pressStart + walkOverworld in a single turn, so the party
                // transitioned TitleOrMenu → Indoors without any Overworld phase
                // observation at a turn boundary, and the boot phase never fired.
                // RAM check (mapId=0 AND party stats > 0) is independent of
                // FfPhase classification timing.
                val bootMapId = ram["currentMapId"] ?: 0
                val bootChar1Str = ram["char1_str"] ?: 0
                if (!outfitBootPhaseDone && bootMapId == 0 && bootChar1Str > 0) {
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
                            // 2026-05-09 cont 3: corridor 6→2. With anchor
                            // captured at (145,155) post-Coneria-exit, radius=6
                            // walks party UP to y=149 — passes through Coneria
                            // CASTLE entry at (145,152) = warps into mapId=8.
                            // Radius=2 keeps corridor at y=153-157 (safe grass
                            // between Coneria town south and castle north).
                            // maxSteps 12→24 to give encounter rate more rolls
                            // (FF1 is ~10% per step, 24 steps ≈ 92% chance).
                            val res = GrindLoop(toolset).invoke(mapOf(
                                "anchorX" to ax.toString(),
                                "anchorY" to ay.toString(),
                                "corridorRadius" to "2",
                                "maxStepsWithoutEncounter" to "24",
                            ))
                            println("[strategy:grind] ok=${res.ok} ${res.message.take(120)}")
                            trace.record(TraceEvent(turn = 0, role = "system", phase = phase.toString(),
                                note = "strategy_grind_result: ok=${res.ok} anchor=($ax,$ay) " +
                                    "msg=${res.message.take(160)}"))
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
                                    trace.record(TraceEvent(turn = 0, role = "system", phase = phase.toString(),
                                        note = "strategy_grind_anchor_shift: #$grindAnchorShifts " +
                                            "($oax,$oay) -> ($nax,$nay)"))
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

        // Scratchpad: mark boot phase start. The kind/summary tags here are
        // significant — `reverseTrajectoryFromTownEntry()` keys off "town_entry"
        // to slice the cardinal-tap log when computing exit reverse.
        run {
            val ram = toolset.getState().ram
            scratchpad.record(kind = "phase",
                summary = "boot_phase_start: town_entry pending",
                smPre = (ram["smPlayerX"] ?: 0) to (ram["smPlayerY"] ?: 0),
                mapflagsPost = ram["mapflags"] ?: 0,
                note = "savestateLoaded=${!System.getenv("KNES_FF1_LOAD_SAVESTATE").isNullOrBlank()} " +
                    "world=(${ram["worldX"]},${ram["worldY"]}) mapId=${ram["currentMapId"]}")
        }

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

        // V5.46.4 (2026-05-09): when KNES_FF1_LOAD_SAVESTATE was honoured
        // pre-boot, the savestate restored party + map state directly inside
        // the shop UI. Walking the overworld + running the nav-advisor would
        // press Up/Down/Left/Right on the active shop dialog, which (a) closes
        // the dialog and (b) eventually lands us on the title screen via FF1's
        // dialog menus. Skip walk + nav-advisor in that case and fall through
        // to the post-enter detect, which already does ShopUiDetector and sets
        // menuAlreadyOpen for BuyAtShop.
        val savestateLoaded = !System.getenv("KNES_FF1_LOAD_SAVESTATE").isNullOrBlank()

        // 2. Walk to Coneria
        if (!savestateLoaded) {
            val walkResult = WalkOverworldTo(toolset, outfitViewportSource, fog).invoke(mapOf(
                "targetX" to (coneriaEntry.worldX ?: 0).toString(),
                "targetY" to (coneriaEntry.worldY ?: 0).toString(),
            ))
            if (!walkResult.ok) {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_outfit_summary: walk_to_coneria_failed: ${walkResult.message}"))
                return
            }
        } else {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_savestate_skip_walk_nav: KNES_FF1_LOAD_SAVESTATE set, skipping walk-to-coneria + nav advisor"))
        }

        // 2b. Settle: give the emulator enough frames for any in-flight warp
        //     transition + town-overlay fade-in to complete BEFORE we hand off
        //     to the advisor. Run #3 (2026-05-09) showed 30 frames was too
        //     short: iter 0 of the advisor caught a partly-rendered town
        //     overlay where party sprite wasn't yet drawn → Gemini answered
        //     "party not visible". 120 frames (~2s @ 60Hz) is enough for the
        //     transition animation to finish and party to render stably.
        //     We do NOT auto-tap cardinal directions to force entry — in
        //     earlier sessions that reactive nudge tripped the party onto the
        //     castle warp tile at world(146,152) and stranded the agent inside
        //     mapId=8 (Coneria CASTLE, NOT a shop).
        toolset.step(buttons = emptyList(), frames = 120)
        val postWalkRam = toolset.getState().ram
        val postWalkMapId = postWalkRam["currentMapId"] ?: 0
        val postWalkMapflags = postWalkRam["mapflags"] ?: 0
        val postWalkSmX = postWalkRam["smPlayerX"] ?: 0
        val postWalkSmY = postWalkRam["smPlayerY"] ?: 0
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_walk_settle: postWalkMapId=$postWalkMapId " +
                   "mapflags=$postWalkMapflags smPlayer=($postWalkSmX,$postWalkSmY)"))

        // 3. Vision-driven advisor: scans the overworld screenshot for the
        //    weapon-shop sign, walks party onto the matching door tile, and
        //    detects shop entry by currentMapId changing from 0 → some non-zero
        //    sub-shop mapId. Coneria's "town" buildings live on the overworld
        //    (mapId=0); each shop is its own sub-mapId entered via its door
        //    tile. mapId=8 is Coneria CASTLE — strictly avoid.
        val initialMapId = postWalkMapId
        if (!savestateLoaded && (initialMapId == 0 || initialMapId == 8)) {
            val maxAdvisorIters = 80
            var entered = false
            var advisorTotalCost = 0.0
            // Rolling action log: each entry records what the advisor told us to do
            // and whether the party position changed afterward. Passed back to the
            // advisor each iteration so it can avoid repeating a blocked direction.
            // Format: "iter N: <action> -> moved (sx,sy)->(sx',sy')" or "iter N: <action> -> NO MOVEMENT".
            data class ActionLogEntry(val iter: Int, val action: String, val before: Pair<Int, Int>, val after: Pair<Int, Int>) {
                fun render(): String {
                    val (bx, by) = before
                    val (ax, ay) = after
                    return if (bx == ax && by == ay) {
                        "iter $iter: $action -> NO MOVEMENT (still at $bx,$by) — direction blocked"
                    } else {
                        "iter $iter: $action -> moved ($bx,$by) -> ($ax,$ay)"
                    }
                }
            }
            val actionLog = ArrayDeque<ActionLogEntry>()
            // V5.39: action log 6 → 30. Run #6 (2026-05-09) showed Gemini
            // visually located the weapon shop in iter 23 but then oscillated
            // (13,8) ↔ (13,9) ↔ (14,8) for 38 iters trying the same blocked
            // cardinals. With only a 6-entry window the model couldn't see
            // it had already attempted Left from (13,8) eight times. 30
            // entries preserves enough history for the model to recognise
            // the cycle and self-correct (or, failing that, gives a clean
            // empirical signal that prompt-only memory isn't enough — at
            // which point we'd graduate to a deterministic BFS fallback).
            val actionLogMaxSize = 30
            var enteredWrongBuildingCount = 0
            // V5.38 minimap: keep a running set of every smPlayer tile we've
            // visited in the town overlay session, plus per-tile blocked-cardinal
            // notes. Run #5 evidence: with only a 6-entry action log Gemini
            // myopically circled INN for 40+ iters re-trying the same handful of
            // tiles. A 2D grid of visited / blocked / current tiles, rendered
            // each iter into the advisor context, lets the LLM see what's
            // already explored and bias toward unexplored frontiers.
            val visitedTiles: MutableSet<Pair<Int, Int>> = mutableSetOf()
            val blockedFrom: MutableMap<Pair<Int, Int>, MutableSet<String>> = mutableMapOf()
            // Render a (2*radius+1)x(2*radius+1) ASCII grid centred on `cur`.
            // Glyphs: '@' = party, 'o' = visited, 'X' = visited and ALL 4
            // cardinals are recorded blocked from there (corner / dead end),
            // '.' = unvisited.
            fun renderMinimap(cur: Pair<Int, Int>, radius: Int = 6): String {
                if (visitedTiles.isEmpty() && cur !in visitedTiles) return ""
                val (cx, cy) = cur
                val sb = StringBuilder()
                sb.append("Town-overlay minimap (radius $radius around party). ")
                sb.append("Glyphs: @=party, o=visited, X=visited and all 4 cardinals tried-blocked from there, .=unvisited. ")
                sb.append("Bias toward '.' tiles.\n")
                sb.append("    ").append((cx - radius..cx + radius).joinToString("") {
                    if (it < 0 || it > 99) "?" else (it % 10).toString()
                }).append('\n')
                for (y in (cy - radius)..(cy + radius)) {
                    sb.append(if (y in 0..99) "%3d ".format(y) else "  ? ")
                    for (x in (cx - radius)..(cx + radius)) {
                        val t = x to y
                        sb.append(when {
                            t == cur -> '@'
                            t in visitedTiles && (blockedFrom[t]?.size ?: 0) >= 4 -> 'X'
                            t in visitedTiles -> 'o'
                            else -> '.'
                        })
                    }
                    sb.append('\n')
                }
                return sb.toString()
            }
            // Position-reading helper. FF1 has THREE coord regimes, distinguished
            // by `mapflags bit 0` ($2D bit 0, "in standard map" per Disch disasm),
            // not by mapId alone:
            //   - genuine overworld:  mapflags.0 = 0          → worldX/worldY
            //   - sub-shop interior:  mapflags.0 = 1, mapId>0 → smPlayerX/smPlayerY
            //   - town overlay:       mapflags.0 = 1, mapId=0 → smPlayerX/smPlayerY
            // Run #3 (2026-05-09) caught the third case empirically: party walked
            // onto Coneria town overlay entry tile, mapflags flipped 2→1, mapId
            // stayed 0, and worldX/Y froze at the entry. Reading worldX/Y here
            // made every advisor tap look "blocked" because the actual movement
            // was happening in smPlayerX/Y. Branch on mapflags so we follow the
            // engine's view of party position regardless of mapId.
            fun readPos(ram: Map<String, Int>, mapId: Int): Pair<Int, Int> {
                val inStandardMap = ((ram["mapflags"] ?: 0) and 0x01) != 0
                return if (!inStandardMap && mapId == 0) {
                    (ram["worldX"] ?: 0) to (ram["worldY"] ?: 0)
                } else {
                    (ram["smPlayerX"] ?: 0) to (ram["smPlayerY"] ?: 0)
                }
            }
            // V5.40 (2026-05-09): shop detection switched from mapId-based to
            // ShopUiDetector. FF1 NES shops are NPC dialog overlays inside the
            // town overlay layer (mapflags.bit0=1, mapId=0), NOT sub-maps. The
            // old check `inSubShop = curMapId != 0 && curMapId != initialMapId`
            // never fired for Coneria shops — run #7 iter 23 confirmed BUY menu
            // open with mapId=0. We now treat regime-only signals (castle ID,
            // mapflags) as cheap rejection gates and let the advisor's Done
            // verdict drive vision-confirmed acceptance below.
            for (iter in 0 until maxAdvisorIters) {
                val ram = toolset.getState().ram
                val curMapId = ram["currentMapId"] ?: 0
                // Castle short-circuit (cheap gate): mapId 8/24 are Coneria
                // Castle, never a shop. Backtrack via Down spam to escape.
                val isCastle = curMapId == 8 || curMapId == 24
                if (isCastle) {
                    enteredWrongBuildingCount++
                    trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                        note = "boot_advisor_castle_shortcircuit[$iter]: mapId=$curMapId — " +
                               "Coneria Castle, not a shop (count=$enteredWrongBuildingCount)"))
                    if (enteredWrongBuildingCount > 3) {
                        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                            note = "boot_advisor_give_up: 3+ wrong buildings entered"))
                        break
                    }
                    repeat(15) {
                        toolset.tap("Down", count = 1, pressFrames = 12, gapFrames = 8)
                        toolset.step(buttons = emptyList(), frames = 6)
                        val nowMid = toolset.getState().ram["currentMapId"] ?: 0
                        if (nowMid == initialMapId || nowMid == 0) return@repeat
                    }
                    actionLog.clear()
                    continue
                }
                val (px, py) = readPos(ram, curMapId)
                val screenshot = toolset.getScreen().base64
                // Diagnostic dump: persist exactly what the advisor sees this iter,
                // so we can post-mortem "party not visible" / wrong-direction calls.
                // Filename encodes both the position regime we read (px,py) AND the
                // raw smPlayerX/Y, so we can verify readPos picked the right regime.
                try {
                    val bytes = java.util.Base64.getDecoder().decode(screenshot)
                    val mapflags = ram["mapflags"] ?: 0
                    val smX = ram["smPlayerX"] ?: 0
                    val smY = ram["smPlayerY"] ?: 0
                    val wX = ram["worldX"] ?: 0
                    val wY = ram["worldY"] ?: 0
                    val fname = "/tmp/spec5-boot-iter-%02d-mid%d-mf%d-pos%d_%d-sm%d_%d-w%d_%d.png"
                        .format(iter, curMapId, mapflags, px, py, smX, smY, wX, wY)
                    java.io.File(fname).writeBytes(bytes)
                } catch (_: Throwable) {}
                val historyText = if (actionLog.isEmpty()) {
                    "(no prior actions — this is the first iteration)"
                } else {
                    actionLog.joinToString(separator = "\n") { it.render() }
                }
                val mapflagsBit0 = ((ram["mapflags"] ?: 0) and 0x01) != 0
                val regime = when {
                    !mapflagsBit0 && curMapId == 0 -> "OVERWORLD"
                    mapflagsBit0 && curMapId == 0 -> "TOWN_OVERLAY"
                    else -> "SUB_MAP"
                }
                // Record current tile as visited (only meaningful in town overlay
                // / sub-map; smPlayer is identity-frozen on the genuine overworld).
                if (regime == "TOWN_OVERLAY" || regime == "SUB_MAP") {
                    visitedTiles += px to py
                }
                val minimapBlock = if (regime == "TOWN_OVERLAY") renderMinimap(px to py) else ""
                val context = buildString {
                    append("Iteration $iter of $maxAdvisorIters. ")
                    append("currentMapId=$curMapId mapflags.bit0=${if (mapflagsBit0) 1 else 0} regime=$regime. ")
                    when (regime) {
                        "OVERWORLD" -> {
                            append("Party is on the OVERWORLD at worldX=$px worldY=$py. ")
                            append("Coneria town buildings are visible around you. ")
                            append("Identify the WEAPON SHOP by its sword/dagger/hammer sign and walk onto its door tile.\n\n")
                        }
                        "TOWN_OVERLAY" -> {
                            append("Party is INSIDE Coneria TOWN OVERLAY at smPlayer($px, $py). ")
                            append("This is the town interior layer — walk between buildings to find the WEAPON SHOP. ")
                            append("FF1 NES shops are NPC dialog overlays: walk adjacent to the shopkeeper and ")
                            append("press A to open the BUY/SELL/EXIT menu. Output Done when you can SEE the ")
                            append("BUY/SELL/EXIT menu (or the WEAPON shopkeeper Welcome dialog) on screen — ")
                            append("currentMapId will stay 0 the whole time, that is normal.\n\n")
                            if (minimapBlock.isNotEmpty()) {
                                append(minimapBlock)
                                append("\n")
                            }
                        }
                        else -> {
                            append("Party is INSIDE a sub-map (mapId=$curMapId) at smPlayer($px, $py). ")
                            append("Walk Up to face the shopkeeper sprite, press A to open BUY/SELL/EXIT, ")
                            append("then output Done.\n\n")
                        }
                    }
                    append("Recent advisor actions (oldest first):\n")
                    append(historyText)
                    append("\n\nRecommend ONE next step.")
                }
                val advisor = outfitAdvisor ?: outfitVision!!
                val firstAdvice = advisor.adviseShopApproach(screenshot, context)
                advisorTotalCost += firstAdvice.costUsd
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_advisor[$iter]: action=${firstAdvice.action} reason=${firstAdvice.reason} costUsd=${firstAdvice.costUsd}"))
                // MANDATORY DOUBLE-CHECK: if Gemini bailed with "party not visible"
                // (or any close paraphrase), force a second look. The party IS in
                // the viewport — town overlay sometimes splits it across multiple
                // small sprites instead of one stacked figure, which trips Pro 2.5's
                // initial scan. A retry with explicit re-examination context usually
                // breaks the false-Fail. Costs ~one extra advisor call (~$0.012).
                val partyDoubtPattern = Regex(
                    "(?i)(party.*(not\\s+visible|not\\s+seen|cannot\\s+(see|find|locate)|" +
                        "isn't\\s+visible|invisible))|" +
                        "((not|cannot|can'?t|unable\\s+to)\\s+(see|find|locate|spot)\\s+.{0,30}party)|" +
                        "(no\\s+party\\s+(visible|sprite|figure))"
                )
                val needsDoubleCheck = firstAdvice.action == "Fail" &&
                    partyDoubtPattern.containsMatchIn(firstAdvice.reason)
                val advice = if (!needsDoubleCheck) firstAdvice else {
                    // Re-fetch screenshot in case the first frame was mid-render,
                    // then re-call advisor with explicit re-examination prompt.
                    toolset.step(buttons = emptyList(), frames = 8)
                    val retryShot = toolset.getScreen().base64
                    val retryContext = buildString {
                        append("DOUBLE CHECK MANDATORY. Your previous response: ")
                        append("\"${firstAdvice.reason.take(120)}\".\n")
                        append("That conclusion is WRONG — the party IS in this viewport. ")
                        append("On the FF1 NES overworld it appears as a 4-character figure stacked at viewport (8,7). ")
                        append("In TOWN OVERLAY (mapflags.bit0=1) the party may render as separate small sprites scattered ")
                        append("near the centre of the visible game area (NOT in the black border / status row). ")
                        append("Look AGAIN — scan tile-by-tile starting from (8,7) outward, and treat ANY humanoid sprite ")
                        append("near the centre as the party. Do NOT output Fail with 'party not visible' again.\n\n")
                        append(context)
                    }
                    val retryAdvice = advisor.adviseShopApproach(retryShot, retryContext)
                    advisorTotalCost += retryAdvice.costUsd
                    trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                        note = "boot_advisor_doublecheck[$iter]: action=${retryAdvice.action} " +
                               "reason=${retryAdvice.reason} costUsd=${retryAdvice.costUsd}"))
                    retryAdvice
                }
                val beforeXY = px to py
                val isMovement = advice.action in setOf("Up", "Down", "Left", "Right")
                when (advice.action) {
                    "Up", "Down", "Left", "Right" ->
                        toolset.tap(advice.action, count = 1, pressFrames = 12, gapFrames = 8)
                    "Tap_A" -> toolset.tap("A", count = 1, pressFrames = 5, gapFrames = 12)
                    "Done" -> {
                        // V5.40: accept Done iff ShopUiDetector confirms a shop
                        // dialog overlay AND the kind matches expected. Run #4
                        // (2026-05-09) entered the ARMOR shop and accepted Done
                        // because kind=armor is a valid shop kind — but boot
                        // phase wants weapons, so all 12 BuyAtShop attempts
                        // failed with WrongClass. Now require kind=="weapon".
                        // V5.45.1: ALSO consult phase classifier. Run #19 trace:
                        // advisor saw BUY/SELL/EXIT (iter 79), but kind classifier
                        // returned null on the same screen (Gemini stochastic
                        // flip). The phase classifier is independent and may
                        // recognize MAIN_MENU when kind misses. Accept Done if
                        // EITHER says shop UI is up.
                        val expectedShopKind = "weapon"
                        val nowRam = toolset.getState().ram
                        val verifyShot = toolset.getScreen().base64
                        val detection = ShopUiDetector.detect(nowRam, verifyShot, outfitVision)
                        advisorTotalCost += detection.costUsd
                        val phaseClass = outfitVision!!.classifyShopMenuPhase(verifyShot)
                        advisorTotalCost += phaseClass.costUsd
                        val phaseSaysOpen = phaseClass.phase != HaikuConsult.ShopMenuPhase.CLOSED &&
                            phaseClass.phase != HaikuConsult.ShopMenuPhase.UNKNOWN
                        val kindMatches = detection.open && detection.kind == expectedShopKind
                        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                            note = "boot_advisor_done_verify[$iter]: open=${detection.open} " +
                                   "source=${detection.source} kind=${detection.kind} " +
                                   "phase=${phaseClass.phase} phaseSaysOpen=$phaseSaysOpen " +
                                   "costUsd=${detection.costUsd + phaseClass.costUsd}"))
                        if (kindMatches || phaseSaysOpen) {
                            entered = true
                            // V5.46: dump savestate so subsequent dev runs can
                            // load it via KNES_FF1_LOAD_SAVESTATE and skip the
                            // pre-boot pressStart + advisor navigation. Saves
                            // ~$1+ per dev iteration on shop-side bugs.
                            try {
                                val bytes = toolset.session.saveState()
                                java.io.File("/tmp/spec5-shop-entered.savestate").writeBytes(bytes)
                                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                                    note = "boot_savestate_dumped: bytes=${bytes.size} " +
                                           "path=/tmp/spec5-shop-entered.savestate " +
                                           "(set KNES_FF1_LOAD_SAVESTATE to load)"))
                            } catch (e: Throwable) {
                                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                                    note = "boot_savestate_dump_failed: ${e.message}"))
                            }
                            break
                        }
                        if (detection.open && detection.kind != null &&
                            detection.kind != expectedShopKind) {
                            // Wrong shop type — exit dialog via B-spam and let
                            // the advisor see the action log entry to learn it
                            // was the WRONG shop. Increment counter so we don't
                            // bounce between wrong shops forever.
                            enteredWrongBuildingCount++
                            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                                note = "boot_advisor_wrong_shop[$iter]: kind=${detection.kind} " +
                                       "(expected=$expectedShopKind); count=$enteredWrongBuildingCount"))
                            if (enteredWrongBuildingCount > 3) {
                                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                                    note = "boot_advisor_give_up: 3+ wrong shops entered"))
                                break
                            }
                            // B-spam to fully exit the wrong-shop dialog stack.
                            repeat(4) {
                                toolset.tap(button = "B", count = 1, pressFrames = 5, gapFrames = 12)
                                toolset.step(buttons = emptyList(), frames = 6)
                            }
                            actionLog.addLast(ActionLogEntry(iter,
                                "Done(rejected-wrong-shop=${detection.kind})", beforeXY, px to py))
                            while (actionLog.size > actionLogMaxSize) actionLog.removeFirst()
                            continue
                        }
                        actionLog.addLast(ActionLogEntry(iter,
                            "Done(rejected-${detection.source})", beforeXY, px to py))
                        while (actionLog.size > actionLogMaxSize) actionLog.removeFirst()
                        continue
                    }
                    "Fail" -> {
                        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                            note = "boot_advisor_terminate: ${advice.action} after $iter iters"))
                        break
                    }
                    else -> {
                        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                            note = "boot_advisor_unknown_action: ${advice.action}"))
                        break
                    }
                }
                toolset.step(buttons = emptyList(), frames = 8)
                // Retry on no-movement: NES walk animation has tile-boundary latency
                // and a single tap after an idle period sometimes fails to register.
                val afterRamRead = toolset.getState().ram
                val afterMid = afterRamRead["currentMapId"] ?: 0
                var (afterX, afterY) = readPos(afterRamRead, afterMid)
                if (isMovement && afterX == px && afterY == py && afterMid == curMapId) {
                    toolset.tap(advice.action, count = 1, pressFrames = 12, gapFrames = 8)
                    toolset.step(buttons = emptyList(), frames = 12)
                    val r2 = toolset.getState().ram
                    val r2Mid = r2["currentMapId"] ?: 0
                    val r2pos = readPos(r2, r2Mid)
                    afterX = r2pos.first
                    afterY = r2pos.second
                }
                actionLog.addLast(ActionLogEntry(iter, advice.action, beforeXY, afterX to afterY))
                while (actionLog.size > actionLogMaxSize) actionLog.removeFirst()
                // 2026-05-09 cont 3: record cardinal taps in scratchpad so the
                // walk-in trajectory persists alongside the savestate. The
                // ExitTownEmpirical skill (and any future reverse-replay) can
                // mine this for known-walkable transitions.
                if (advice.action in setOf("Up", "Down", "Left", "Right")) {
                    scratchpad.record(kind = "tap",
                        summary = "boot_walk_in: ${advice.action}",
                        dir = advice.action.uppercase(),
                        smPre = beforeXY,
                        smPost = afterX to afterY,
                        mapflagsPost = afterRamRead["mapflags"] ?: 0,
                        note = if (regime == "TOWN_OVERLAY") "town_overlay" else regime)
                }
                // Minimap update: tag this direction as blocked-from-this-tile
                // when the action was a cardinal that produced no movement and
                // we stayed in the same map regime (so the block is geometric,
                // not a regime-transition like entering a sub-shop).
                if (isMovement && afterX == px && afterY == py && afterMid == curMapId) {
                    blockedFrom.getOrPut(px to py) { mutableSetOf() } += advice.action
                }
            }
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_advisor_summary: entered=$entered totalCostUsd=$advisorTotalCost"))
            if (!entered) {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_outfit_summary: advisor_failed_to_enter_shop"))
                return
            }
            // Update cached weapon-shop landmark with the mapId we ended up in.
            // For hypothesis B (Coneria), this is mapId=8 itself (no sub-shop).
            // For towns with real sub-shops, this is the sub-shop mapId. BuyAtShop
            // precondition `landmark.mapId == currentMapId` passes either way.
            val postRam = toolset.getState().ram
            val postMapId = postRam["currentMapId"] ?: 0
            val postX = postRam["smPlayerX"] ?: 0
            val postY = postRam["smPlayerY"] ?: 0
            val cached = landmarkMemory.findByKind(LandmarkKind.NPC_SHOPKEEPER)
                .firstOrNull { it.note.contains("kind=weapon") }
            if (cached != null && cached.mapId != postMapId) {
                landmarkMemory.recordIfNew(cached.copy(
                    mapId = postMapId,
                    localX = postX,
                    localY = postY,
                    visited = true,
                    note = cached.note + "; entered_via=opus-advisor",
                ))
                landmarkMemory.save()
            }
        }

        // 3c. Spec 5: post-enter vision-driven approach. Run 13 confirmed
        //     EnterShop reaches a sub-shop (mapId=24) but blocks at a wall
        //     before the keeper, so BuyAtShop's tap-A loop never opens the
        //     BUY menu. Use Pass 1 vision to spot the shopkeeper in the
        //     screen and walk party to (sx, sy+1) facing N — same Spec 4
        //     pipeline already proven in §empirical run 8.
        var menuAlreadyOpen = false
        run {
            val screenshot = toolset.getScreen().base64
            // Always dump post-enter screenshot for diagnostic — gives visual
            // evidence of which shop we are in.
            try {
                val bytes = java.util.Base64.getDecoder().decode(screenshot)
                java.io.File("/tmp/spec5-postenter.png").writeBytes(bytes)
            } catch (_: Throwable) {}
            // V5.40: if shop UI is ALREADY open (advisor accepted Done because
            // BUY/SELL/EXIT was visible), skip the keeper-approach walk —
            // cardinals in an open shop menu move the menu cursor, not the
            // party. Detector reuses RAM gate + classifyShopMenu.
            val postEnterRam = toolset.getState().ram
            val postEnterDetect = ShopUiDetector.detect(postEnterRam, screenshot, outfitVision)
            // V5.44.2 (2026-05-09): in addition to the kind-classifier (which
            // is stochastic and has flipped between open/closed on identical
            // screens — runs #10, #15), also consult the menu-phase classifier
            // and treat ANY non-CLOSED phase as "shop UI is up". This catches
            // the case where kind-classifier returns unknown but the dialog is
            // genuinely on screen. Run #15 evidence: post_enter said closed,
            // keeper_approach walked cardinals into the open menu, state
            // machine couldn't recover and all 4 pairs got ShopClosed.
            val postEnterPhase = outfitVision!!.classifyShopMenuPhase(screenshot)
            val phaseSaysOpen = postEnterPhase.phase != HaikuConsult.ShopMenuPhase.CLOSED &&
                postEnterPhase.phase != HaikuConsult.ShopMenuPhase.UNKNOWN
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_post_enter_detect: open=${postEnterDetect.open} " +
                       "source=${postEnterDetect.source} kind=${postEnterDetect.kind} " +
                       "phase=${postEnterPhase.phase} phaseSaysOpen=$phaseSaysOpen"))
            if ((postEnterDetect.open && postEnterDetect.kind == "weapon") || phaseSaysOpen) {
                // Either classifier confirms shop UI on screen — skip the
                // cardinals walk because cardinals navigate menu cursor not
                // party when dialog is open.
                menuAlreadyOpen = true
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_keeper_approach: skipped — shop UI on screen " +
                           "(kind=${postEnterDetect.kind} phase=${postEnterPhase.phase}); " +
                           "BuyAtShop will run with menuAlreadyOpen=true"))
                return@run
            }
            val scan = outfitVision!!.scanInteriorCandidates(screenshot)
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_post_enter_scan: count=${scan.candidates.size} " +
                       "kinds=[${scan.candidates.joinToString(",") { it.kind }}] " +
                       "costUsd=${scan.costUsd} (screenshot: /tmp/spec5-postenter.png)"))
            val keeper = scan.candidates
                .filter { it.kind == "shopkeeper" }
                .maxByOrNull { it.confidence }
            if (keeper != null) {
                // Party renders at viewport tile (8, 7). Walk so party stands at
                // (sx, sy + 1) facing N — directly south of keeper.
                val dx = keeper.screenX - 8
                val dyTarget = (keeper.screenY + 1) - 7
                val xDir = if (dx < 0) "Left" else "Right"
                val yDir = if (dyTarget < 0) "Up" else "Down"
                repeat(kotlin.math.abs(dx)) {
                    toolset.tap(button = xDir, count = 1, pressFrames = 12, gapFrames = 8)
                    toolset.step(buttons = emptyList(), frames = 8)
                }
                repeat(kotlin.math.abs(dyTarget)) {
                    toolset.tap(button = yDir, count = 1, pressFrames = 12, gapFrames = 8)
                    toolset.step(buttons = emptyList(), frames = 8)
                }
                // Final N-tap so facing stays N (in case last move was horizontal
                // or a Down for descending toward keeper).
                toolset.tap(button = "Up", count = 1, pressFrames = 12, gapFrames = 8)
                toolset.step(buttons = emptyList(), frames = 8)
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_keeper_approach: keeper_screenXY=(${keeper.screenX},${keeper.screenY}) " +
                           "walked dx=$dx dy=$dyTarget"))
            } else {
                // Diagnostic dump: save the post-enter screenshot for manual inspection.
                try {
                    val bytes = java.util.Base64.getDecoder().decode(screenshot)
                    java.io.File("/tmp/spec5-postenter-no-keeper.png").writeBytes(bytes)
                } catch (_: Throwable) {}
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_keeper_approach: NO_SHOPKEEPER_IN_VIEW; " +
                           "screenshot dumped to /tmp/spec5-postenter-no-keeper.png; " +
                           "BuyAtShop will likely fail"))
            }
        }

        // 4. Per-char buy loop — V5.43: single invokeMany call drives ALL 4
        // chars in one continuous shop dialog so NPC drift between chars no
        // longer breaks re-engagement (run #9 evidence: char1 succeeded then
        // shopkeeper wandered off-screen, char2/3/4 reengage hit
        // NO_SHOPKEEPER_IN_VIEW). Pairs are constructed with the same
        // class-naive mapping as before (char N → slot N-1); the next escalation
        // is class-aware mapping (see HANDOFF §"Remaining work").
        val buySkill = BuyAtShop(toolset, landmarkMemory)
        val charsBought = mutableListOf<Int>()
        val initialGold = StrategyContext.totalGold(toolset.getState().ram)
        // Default mapping: each char gets one of the 4 first inventory slots.
        val weaponSlotByChar = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3)
        // V5.42 (2026-05-09): keeper re-approach helper. NPCs in FF1 NES towns
        // walk between tiles each frame; during BuyAtShop's 30+ A/Down/B taps
        // the shopkeeper has typically moved away from the previously-engaged
        // tile. This helper re-runs the vision scan and walks party adjacent
        // to wherever the keeper is now. Returns true if keeper found and walk
        // attempted (caller should then probe shop UI). Returns false if no
        // keeper visible (next BuyAtShop will fail; caller should bail).
        // Diagnostic: write a base64-png to /tmp under a stable name so any
        // failure stage can be inspected after the fact. Best-effort; never
        // throws.
        fun dumpShot(name: String, base64: String) {
            try {
                val bytes = java.util.Base64.getDecoder().decode(base64)
                java.io.File("/tmp/spec5-$name.png").writeBytes(bytes)
            } catch (_: Throwable) {}
        }
        suspend fun reEngageKeeper(tag: String): Boolean {
            val shot = toolset.getScreen().base64
            dumpShot("buy-$tag-reengage-pre", shot)
            val scan = outfitVision!!.scanInteriorCandidates(shot)
            val keeper = scan.candidates
                .filter { it.kind == "shopkeeper" }
                .maxByOrNull { it.confidence }
            if (keeper == null) {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_keeper_reengage[$tag]: NO_SHOPKEEPER_IN_VIEW " +
                           "candidates=${scan.candidates.size} costUsd=${scan.costUsd} " +
                           "(screenshot: /tmp/spec5-buy-$tag-reengage-pre.png)"))
                return false
            }
            val dx = keeper.screenX - 8
            val dyTarget = (keeper.screenY + 1) - 7
            val xDir = if (dx < 0) "Left" else "Right"
            val yDir = if (dyTarget < 0) "Up" else "Down"
            repeat(kotlin.math.abs(dx)) {
                toolset.tap(button = xDir, count = 1, pressFrames = 12, gapFrames = 8)
                toolset.step(buttons = emptyList(), frames = 8)
            }
            repeat(kotlin.math.abs(dyTarget)) {
                toolset.tap(button = yDir, count = 1, pressFrames = 12, gapFrames = 8)
                toolset.step(buttons = emptyList(), frames = 8)
            }
            // Final Up — walking INTO the keeper tile triggers the dialog
            // automatically in FF1 town overlay (no Tap_A needed).
            toolset.tap(button = "Up", count = 1, pressFrames = 12, gapFrames = 8)
            toolset.step(buttons = emptyList(), frames = 12)
            dumpShot("buy-$tag-reengage-post", toolset.getScreen().base64)
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_keeper_reengage[$tag]: keeper_screenXY=(${keeper.screenX},${keeper.screenY}) " +
                       "walked dx=$dx dy=$dyTarget costUsd=${scan.costUsd} " +
                       "(screenshots: /tmp/spec5-buy-$tag-reengage-{pre,post}.png)"))
            return true
        }
        // V5.44.3: trust menuAlreadyOpen from run{} block. It used the dual
        // classifier (kind + phase) check. Re-probing here just adds another
        // chance for stochastic flip → unwanted reengage walk → scrambled
        // menu cursor. Only reengage if menuAlreadyOpen is false.
        var batchMenuOpen = menuAlreadyOpen
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_purchase_batch_initial: menuOpen=$batchMenuOpen " +
                   "(trusting run{} block's dual-classifier decision)"))
        if (!batchMenuOpen) {
            val reEngaged = reEngageKeeper(tag = "batch-pre")
            if (reEngaged) {
                val rs = toolset.getScreen().base64
                val probe2 = ShopUiDetector.detect(toolset.getState().ram, rs, outfitVision)
                val phase2 = outfitVision!!.classifyShopMenuPhase(rs)
                val phase2SaysOpen = phase2.phase != HaikuConsult.ShopMenuPhase.CLOSED &&
                    phase2.phase != HaikuConsult.ShopMenuPhase.UNKNOWN
                batchMenuOpen = (probe2.open && probe2.kind == "weapon") || phase2SaysOpen
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_purchase_batch_reengage_probe: menuOpen=$batchMenuOpen " +
                           "kind=${probe2.kind} phase=${phase2.phase}"))
            }
        }
        dumpShot("buy-batch-pre", toolset.getScreen().base64)
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_purchase_batch_probe: menuAlreadyOpen=$batchMenuOpen " +
                   "(screenshot: /tmp/spec5-buy-batch-pre.png)"))

        // Build initial pair list (one per char missing a weapon). We do up to
        // 4 invokeMany rounds: round 0 uses default mapping, subsequent rounds
        // shift slot by +1 mod 4 for any char still without a weapon. Each
        // invokeMany round runs in a single shop dialog so NPC drift between
        // pairs no longer matters.
        val MAX_BATCH_ROUNDS = 4
        // charSlot -> attempted slot for the current round.
        val pendingChars: MutableMap<Int, Int> = (1..4)
            .filter { !StrategyContext.anyWeaponEquipped(toolset.getState().ram, it) }
            .associateWith { weaponSlotByChar[it] ?: 0 }
            .toMutableMap()

        // V5.45: vision-advisor drives the in-shop purchase. Read each char's
        // class from RAM so the advisor can pick class-compatible items. Class
        // is at $6100/$6140/$6180/$61C0 (FF1 disasm) — values 0..5 for
        // Fighter / Thief / BlackBelt / RedMage / WhiteMage / BlackMage.
        val classRam = toolset.getState().ram
        val charClasses: Map<Int, Int> = (1..4).associateWith { c ->
            classRam["char${c}_class"] ?: 0
        }
        val charsNeeding: List<Int> = pendingChars.keys.toList().sorted()
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_purchase_advisor_start: chars=$charsNeeding " +
                   "classes=${charClasses.entries.joinToString { "char${it.key}=${it.value}" }}"))
        val advisorBought = buySkill.invokeWithAdvisor(
            charSlotsNeedingWeapons = charsNeeding,
            charClasses = charClasses,
            expectedKeeperKind = "weapon",
            vision = outfitVision!!,
            traceLog = { msg ->
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_purchase_advisor: $msg"))
            },
            // Run A2 (post-NameTable-fix): 3/4 chars BOUGHT in 80 iter with the
            // POST-PURCHASE-FLOW prompt update. char3 finished at iter 75 leaving
            // no headroom for char4. 120 gives all 4 chars room plus recovery
            // budget for occasional cursor mis-reads on FOR_WHOM/BUY_CONFIRM.
            maxAdvisorCalls = 120,
        )
        for ((charSlot, wasBought) in advisorBought) {
            if (wasBought) charsBought += charSlot
        }
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_purchase_advisor_done: bought=$charsBought of needs=$charsNeeding"))
        run {
            val ram = toolset.getState().ram
            scratchpad.record(kind = "phase",
                summary = "shop_reached: buy_advisor_done bought=$charsBought",
                smPre = (ram["smPlayerX"] ?: 0) to (ram["smPlayerY"] ?: 0),
                mapflagsPost = ram["mapflags"] ?: 0)
        }

        // 2026-05-09 cont 3: dump savestate post-buy so subsequent dev runs can
        // skip the ~5-min buy advisor and iterate on exit+grind directly. Mirror
        // of the spec5-shop-entered.savestate pattern above. Wrapped in try/catch
        // — failure to dump is dev-tool noise, not a session-blocking error.
        try {
            val bytes = toolset.session.saveState()
            val ssFile = java.io.File("/tmp/spec5-post-buy.savestate")
            ssFile.writeBytes(bytes)
            // Coding-agent-style scratchpad sister file. Captures the action log
            // up to this savestate so subsequent runs can reason about "where
            // we came from" without burning an LLM call to figure it out.
            val padFile = AgentScratchpad.sisterPathFor(ssFile)
            scratchpad.record(kind = "summary",
                summary = "post_buy_savestate_dumped",
                note = "bought=$charsBought spent=${initialGold - StrategyContext.totalGold(toolset.getState().ram)}G")
            scratchpad.save(padFile)
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_post_buy_savestate_dumped: bytes=${bytes.size} " +
                       "path=${ssFile.absolutePath} sister=${padFile.absolutePath} " +
                       "scratchpad_entries=${scratchpad.all().size} " +
                       "(set KNES_FF1_LOAD_SAVESTATE to load and skip buy)"))
        } catch (e: Throwable) {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_post_buy_savestate_dump_failed: ${e.message}"))
        }

        // Legacy stateful-batch retry path — only used if advisor served zero
        // chars (e.g., advisor immediately gave up). Kept as a safety net.
        var roundIdx = 0
        var roundMenuOpen = false  // advisor exit B-spammed; menu likely closed
        while (charsBought.isEmpty() && pendingChars.isNotEmpty() && roundIdx < MAX_BATCH_ROUNDS) {
            val pairs = pendingChars.entries.map { it.value to it.key } // (itemSlot, charSlot)
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_purchase_batch[$roundIdx]: pairs=${pairs.joinToString { "(s${it.first},c${it.second})" }} " +
                       "menuAlreadyOpen=$roundMenuOpen mode=stateful_fallback"))
            val results = buySkill.invokeManyStateful(
                pairs = pairs,
                expectedKeeperKind = "weapon",
                vision = outfitVision!!,
                traceLog = { msg ->
                    trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                        note = "boot_purchase_state[$roundIdx]: $msg"))
                },
            )
            dumpShot("buy-batch-r$roundIdx-post", toolset.getScreen().base64)
            for (res in results) {
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_purchase: char${res.charSlot} slot=${res.itemSlot} round=$roundIdx " +
                           "result=${res.message}"))
                if (res.ok) {
                    charsBought += res.charSlot
                    pendingChars.remove(res.charSlot)
                } else if (res.message.contains("WrongClass")) {
                    // Cycle to next slot for retry next round.
                    pendingChars[res.charSlot] = ((pendingChars[res.charSlot] ?: 0) + 1) % 4
                } else {
                    // Structural failure (NotInShop, etc) — abort the rest.
                    pendingChars.remove(res.charSlot)
                }
            }
            // After invokeMany's final 5-B exit, shop dialog is closed. For
            // the next round we must re-engage the keeper.
            if (pendingChars.isNotEmpty()) {
                val reEngaged = reEngageKeeper(tag = "batch-r${roundIdx + 1}-pre")
                if (!reEngaged) {
                    trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                        note = "boot_purchase_batch[$roundIdx]: re-engage failed, aborting"))
                    break
                }
                val nextProbeShot = toolset.getScreen().base64
                val nextProbe = ShopUiDetector.detect(toolset.getState().ram, nextProbeShot, outfitVision)
                roundMenuOpen = nextProbe.open && nextProbe.kind == "weapon"
            }
            roundIdx++
        }

        // 5. Exit shop + town to overworld via vision-LLM (Phase 3).
        // Per spec 2026-05-09-coneria-pipeline: vision is the sole hot-path
        // exit. ExitTownEmpirical / tree-detour stay reachable only when the
        // navigator is unwired (offline tests).
        repeat(8) {
            toolset.tap(button = "B", count = 1, pressFrames = 4, gapFrames = 8)
            toolset.step(buttons = emptyList(), frames = 4)
        }

        val exitResult = if (outfitNavigator != null) {
            knes.agent.skills.WalkInteriorVision(
                toolset, outfitNavigator, toolCallLog,
                historyHint = scratchpad.renderForLLM(),
            ).invoke(mapOf("maxSteps" to "60"))
        } else {
            // Offline fallback: empirical+tree-detour, then BFS.
            val emp = knes.agent.skills.ExitTownEmpirical(toolset, scratchpad)
                .invoke(mapOf("maxTaps" to "120"))
            if (emp.ok) emp
            else ExitInterior(toolset, outfitMapSession, fog).invoke(emptyMap())
        }
        val exitRam = exitResult.ramAfter
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_phase3_exit_result: ok=${exitResult.ok} msg=${exitResult.message} " +
                "world=(${exitRam["worldX"] ?: "?"},${exitRam["worldY"] ?: "?"}) " +
                "mapflags=${exitRam["mapflags"] ?: "?"} " +
                "via=${if (outfitNavigator != null) "vision" else "fallback"}"))
        scratchpad.record(kind = "exit",
            summary = "phase3_exit_result: ok=${exitResult.ok}",
            smPost = (exitRam["smPlayerX"] ?: 0) to (exitRam["smPlayerY"] ?: 0),
            mapflagsPost = exitRam["mapflags"] ?: 0,
            note = exitResult.message.take(120))

        // Phase 4: grind anchor selection + GrindLoop with re-anchor.
        // Only run when exit succeeded AND viewport source is an OverworldMap.
        val owMap = outfitViewportSource as? knes.agent.perception.OverworldMap
        if (exitResult.ok && owMap != null) {
            // 120-frame settle so any mapflags.bit1 mid-scroll transient clears.
            toolset.step(buttons = emptyList(), frames = 120)
            val ramSettled = toolset.getState().ram
            val partyXY = (ramSettled["worldX"] ?: 0) to (ramSettled["worldY"] ?: 0)
            val triedAnchors = mutableSetOf<Pair<Int, Int>>()
            val firstPick = GrindAnchorSelector.pickGrindAnchor(partyXY, owMap)
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_phase4_grind_anchor: anchor=(${firstPick.anchor.first}," +
                    "${firstPick.anchor.second}) tileClass=${firstPick.tileClass} " +
                    "fellBack=${firstPick.fellBack} party=(${partyXY.first},${partyXY.second})"))
            var grindAttempt = 0
            val maxAttempts = 3  // 1 initial + 2 re-anchors
            var lastResult: knes.agent.skills.SkillResult? = null
            var currentPick = firstPick
            while (grindAttempt < maxAttempts) {
                triedAnchors += currentPick.anchor
                val (ax, ay) = currentPick.anchor
                lastResult = knes.agent.skills.GrindLoop(toolset).invoke(mapOf(
                    "anchorX" to ax.toString(),
                    "anchorY" to ay.toString(),
                    "corridorRadius" to "3",
                    "maxStepsWithoutEncounter" to "12",
                ))
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_phase4_grind_result: attempt=${grindAttempt + 1}/$maxAttempts " +
                        "anchor=($ax,$ay) outcome=${lastResult.message}"))
                val msg = lastResult.message
                val battle = msg.startsWith("EncounteredBattle")
                if (battle) break
                val noEnc = msg.startsWith("NoEncounter")
                if (!noEnc) break  // WanderedOff / Blocked → don't re-anchor
                grindAttempt++
                if (grindAttempt >= maxAttempts) break
                // Re-anchor: read current world coord and pick the next-best
                // tile that we haven't tried yet. Loosen by widening radius.
                val reRam = toolset.getState().ram
                val reParty = (reRam["worldX"] ?: ax) to (reRam["worldY"] ?: ay)
                val widerPicks = (1..3).asSequence().mapNotNull { r ->
                    val p = GrindAnchorSelector.pickGrindAnchor(reParty, owMap, radius = r + 1)
                    if (p.anchor in triedAnchors || p.fellBack) null else p
                }
                currentPick = widerPicks.firstOrNull() ?: break
                trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                    note = "boot_phase4_grind_reanchor: attempt=${grindAttempt + 1} " +
                        "newAnchor=(${currentPick.anchor.first},${currentPick.anchor.second}) " +
                        "tileClass=${currentPick.tileClass}"))
            }
            val pipelineVictory = lastResult?.message?.startsWith("EncounteredBattle") == true
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_pipeline_end: victory=$pipelineVictory " +
                    "lastPhase=${if (pipelineVictory) "phase4_battle_pending" else "phase4_grind"}"))
        } else if (!exitResult.ok) {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_pipeline_end: victory=false lastPhase=phase3_exit_failed"))
        } else {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_pipeline_end: victory=false lastPhase=phase4_skipped_no_overworld_map"))
        }

        // Per 2026-05-09 cont 2 handoff: skip equip by default. Chars can grind
        // bare-handed (lower DPS but works); equip is a separate follow-up since
        // EquipWeapon is a brittle deterministic state-machine that returned
        // MenuStuck × 4 in Run B2-v3, costing ~240 A-taps before the strategic
        // loop reached GRIND. Set KNES_FF1_BOOT_EQUIP=1 to re-enable.
        val charsEquipped = mutableListOf<Int>()
        if (System.getenv("KNES_FF1_BOOT_EQUIP") == "1") {
            val equipSkill = EquipWeapon(toolset)
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
        } else {
            trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
                note = "boot_outfit_equip_skipped: KNES_FF1_BOOT_EQUIP not set; bare-handed grind enabled"))
        }

        // 6. Summary.
        val finalGold = StrategyContext.totalGold(toolset.getState().ram)
        val goldSpent = (initialGold - finalGold).coerceAtLeast(0)
        val summary = "weaponsBought=${charsBought.size} " +
            "weaponsEquipped=${charsEquipped.size} totalGoldSpent=$goldSpent"
        println("[boot_outfit] boot_outfit_summary: $summary")
        trace.record(TraceEvent(turn = 0, role = "system", phase = "BOOT",
            note = "boot_outfit_summary: $summary"))
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
