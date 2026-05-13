package knes.agent.v2.agents

import knes.agent.llm.AnthropicSession
import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.llm.SonnetClient
import knes.agent.v2.runtime.MilestonePredicates
import knes.agent.v2.runtime.Plan
import knes.agent.v2.runtime.PlanStep
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory
import knes.agent.v2.tools.ToolOutcome
import knes.agent.v2.tools.ToolSurface

data class ExecutorDecision(
    val tool: String,
    val args: Map<String, String>,
    val reasoning: String,
    val outcome: ToolOutcome,
    val ms: Long,
)

/**
 * Per-turn tool picker. Plan-hint dispatch is the default fast path; when the
 * plan exhausts or recent steps keep failing, Sonnet decides what to do next —
 * informed by a Haiku scene description (Option A: vision pre-processed by
 * Haiku, Sonnet reads the text digest).
 */
class ExecutorAgent(
    private val anthropic: AnthropicSession,
    private val sonnet: SonnetClient,  // kept for fallback / future toggle, currently unused
    private val haiku: HaikuClient,
    private val tools: ToolSurface,
    private val memory: V2Memory,
    private val run: V2RunDirectory? = null,
    private val gemini: GeminiPro31Client? = null,
) {
    private val recentOutcomes = ArrayDeque<String>(4)
    private val recentMoves = ArrayDeque<MoveEntry>(8)
    private var lastPlanCreatedAt: Int = -1
    private var currentTurn: Int = 0

    private data class MoveEntry(
        val turn: Int,
        val preSm: Pair<Int, Int>,
        val tool: String,
        val argsSummary: String,
        val postSm: Pair<Int, Int>,
        val outcome: String,
    )

    suspend fun act(screenshotB64: String, ramDigest: String, turn: Int = 0): ExecutorDecision {
        val started = System.currentTimeMillis()
        currentTurn = turn
        run?.markActive("executor", turn)
        val preRam = parseSmFromRamDigest(ramDigest)
        val plan = memory.currentPlan
        val planCreatedAt = plan?.createdAtTurn ?: -1
        if (planCreatedAt != lastPlanCreatedAt) {
            recentOutcomes.clear()
            lastPlanCreatedAt = planCreatedAt
        }
        // Sonnet-every-turn (2026-05-12 architecture change): plan-hint dispatch
        // disabled. Plan is included in Sonnet's prompt as a SUGGESTION the
        // strategic Advisor made, but Sonnet sees the current screenshot and
        // decides per-turn whether the plan step fits the actual game state.
        //
        // Prior plan-hint path silently dispatched walkTo(11,10) from any phase
        // (turn 2026-05-12-2034 trace: plan said town-local coords, executed
        // from Overworld → walkOverworldTo(11,10) walked NW into Coneria
        // CASTLE; Sonnet was never consulted until 2 fails, by which time
        // party was already trapped in mapId=24 throne room).
        val (tool, args, reasoning) = askLlm(plan, screenshotB64, ramDigest)

        val outcome = dispatch(tool, args)
        recentOutcomes.addLast(outcome.javaClass.simpleName)
        if (recentOutcomes.size > 4) recentOutcomes.removeFirst()
        // Record move history for next turn's anti-oscillation prompt context.
        // Read post-state straight from outcome message (sequence/townWalk both
        // include sm coords in their Ok messages) — not bullet-proof but cheap
        // and avoids another toolset.getState() round-trip.
        val postRam = if (outcome is ToolOutcome.Ok) extractSmFromMessage(outcome.message) ?: preRam else preRam
        recentMoves.addLast(MoveEntry(
            turn = turn, preSm = preRam, tool = tool,
            argsSummary = args.entries.joinToString(",") { "${it.key}=${it.value.take(40)}" },
            postSm = postRam,
            outcome = outcome.javaClass.simpleName,
        ))
        if (recentMoves.size > 8) recentMoves.removeFirst()

        if (outcome is ToolOutcome.Ok && plan != null) advancePlan(plan)

        run?.markIdle()
        return ExecutorDecision(tool, args, reasoning, outcome, System.currentTimeMillis() - started)
    }

    private fun parseSmFromRamDigest(digest: String): Pair<Int, Int> {
        val sx = Regex("smPlayerX=(-?\\d+)").find(digest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sy = Regex("smPlayerY=(-?\\d+)").find(digest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return sx to sy
    }

    private fun extractSmFromMessage(msg: String): Pair<Int, Int>? {
        // Both sequence (.message contains "sm=(X,Y)") and townWalk Ok messages
        // include "sm=(N,N)". Pull the first match.
        val m = Regex("sm=\\((-?\\d+),(-?\\d+)\\)").find(msg) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /**
     * Sonnet decides every turn. Sees screenshot + Haiku digest + RAM digest +
     * plan (as suggestion, not authority). Network/parse failures fall back to
     * plan tail so the emulator keeps stepping (otherwise the watchdog freezes).
     */
    private suspend fun askLlm(plan: Plan?, screenshotB64: String, ramDigest: String): Triple<String, Map<String, String>, String> {
        return try {
            // 2026-05-12: dropped Haiku scene digest pre-processing — Gemini reads
            // the screenshot directly. Haiku was misclassifying field-menu as
            // battle and infecting Sonnet/Gemini downstream. Per user feedback
            // "niech gemini sobie samo czyta screena". Haiku stays in tool
            // internals (directionTo, scanCandidates) where the targeted prompts
            // are accurate.
            val userText = buildSonnetUserText(plan, scene = "", ramDigest)
            val combinedPrompt = "$EXECUTOR_SYSTEM_PROMPT\n\n$userText"
            val raw = (gemini?.generate(combinedPrompt, imageB64 = screenshotB64)
                ?: sonnet.decideTool(EXECUTOR_SYSTEM_PROMPT, userText, imageB64 = screenshotB64))
            val modelTag = if (gemini != null) "GEMINI" else "SONNET"
            runCatching {
                run?.promptFile(currentTurn, "executor")?.toFile()?.writeText(
                    "=== $modelTag SYSTEM ===\n$EXECUTOR_SYSTEM_PROMPT\n\n" +
                    "=== $modelTag USER ===\n$userText\n\n" +
                    "=== $modelTag RAW RESPONSE ===\n$raw"
                )
            }
            parseToolDecision(raw)
        } catch (e: Exception) {
            System.err.println("[v2.executor] askLlm error: ${e.message?.take(160)} — falling back to plan tail")
            val tail = plan?.steps?.lastOrNull()
            if (tail?.intentTool != null) Triple(tail.intentTool, tail.intentArgs ?: emptyMap(), "fallback to plan tail (askLlm exception)")
            else Triple("useMenu", mapOf("path" to "main/exit"), "fallback no-op (no plan, askLlm exception)")
        }
    }

    private fun buildSonnetUserText(plan: Plan?, @Suppress("UNUSED_PARAMETER") scene: String, ramDigest: String): String {
        val currentMilestone = memory.campaign.milestones.firstOrNull { it.status == "in_progress" }?.id ?: "(none)"
        val milestoneStates = memory.campaign.milestones.joinToString(" ") { "${it.id}=${it.status}" }
        val planSummary = if (plan == null) "(no plan)" else {
            val cur = plan.steps.getOrNull(plan.cursor)
            val tail = plan.steps.lastOrNull()
            buildString {
                val staleNote = if (plan.milestone != currentMilestone) " [STALE — plan was for ${plan.milestone}, current is $currentMilestone]" else ""
                append("plan-milestone=${plan.milestone} cursor=${plan.cursor}/${plan.steps.size}$staleNote\n")
                if (cur != null) append("current step [${cur.index}]: ${cur.intentTool}(${cur.intentArgs ?: emptyMap()}) — ${cur.description}\n")
                if (tail != null && tail.index != cur?.index) append("final step [${tail.index}]: ${tail.intentTool}(${tail.intentArgs ?: emptyMap()}) — ${tail.description}\n")
            }
        }
        val recent = if (recentOutcomes.isEmpty()) "(none)" else recentOutcomes.joinToString(",")
        val movesBlock = if (recentMoves.isEmpty()) "(no prior moves yet)" else {
            recentMoves.joinToString("\n") { mv ->
                val moved = mv.preSm != mv.postSm
                val tag = if (moved) "MOVED" else "NO-MOVEMENT"
                "  T${mv.turn}: sm${mv.preSm} → ${mv.tool}(${mv.argsSummary.take(50)}) [${mv.outcome}] → sm${mv.postSm} [$tag]"
            }
        }
        // Detect oscillation: same postSm in last 3+ moves means walls are
        // blocking the issued direction — agent should take a longer detour
        // around obstacles, not retry the same tap.
        val stuckSm = recentMoves.takeLast(3)
            .takeIf { it.size >= 3 }
            ?.let { window -> if (window.all { it.postSm == window.first().postSm }) window.first().postSm else null }
        val antiOscillation = if (stuckSm != null) {
            "\n  *** ANTI-OSCILLATION: party stuck at sm=$stuckSm for ${recentMoves.takeLast(8).count { it.postSm == stuckSm }} of last ${recentMoves.size} moves. " +
            "The directions you've been trying are BLOCKED. Walk AROUND the obstacle (perpendicular axis, longer detour) — do NOT repeat the same tile-blocked taps. ***"
        } else ""
        // Parse RAM digest into a map for the party-weapon helper.
        val ramMap = mutableMapOf<String, Int>()
        for (pair in ramDigest.split(',')) {
            val eq = pair.indexOf('='); if (eq < 0) continue
            ramMap[pair.substring(0, eq)] = pair.substring(eq + 1).toIntOrNull() ?: continue
        }
        val party = MilestonePredicates.partyWeaponDigest(ramMap)
        return """
            CURRENT GOAL (milestone in progress): $currentMilestone
            All milestones: $milestoneStates

            Party weapons: $party
            (held bytes are item indices; * suffix = equipped, bit7 of byte)

            RAM digest:
            $ramDigest

            Recent moves (last ${recentMoves.size}, oldest first):
            $movesBlock$antiOscillation

            Current plan context (Advisor SUGGESTION — verify against screenshot):
            $planSummary

            Recent executor outcomes (last 4): $recent

            Output ONE of these JSON shapes (no prose, JSON only):

            (A) Raw button sequence — PREFERRED for navigation, building entry, dialog stepping.
                {"sequence":["Up"],"reasoning":"<≤80 chars why>"}
                Allowed buttons: Up, Down, Left, Right, A, B, START, SELECT.
                EXACTLY ONE BUTTON per turn. The FF1 NES viewport is 16×14 tiles,
                NPCs and walls block frequently — longer sequences overshoot or
                hit obstacles before you see the result. You'll re-read the
                screen and pick the next single button next turn (no extra LLM
                cost — Executor runs every turn anyway).

            (B) High-level tool — for compound flows the engine handles in one call.
                {"tool":"<one of: buyAtShop|equipWeapon|restAtInn|useMenu>","args":{...},"reasoning":"..."}
                  buyAtShop:    args = {"items":"3,2,1,0","charSlots":"0,1,2,3"}
                  equipWeapon:  args = {"charSlot":"<0-3>","weaponSlot":"<0-3>"}
                  restAtInn:    args = {"innMapId":"<int>"}
                  useMenu:      args = {"path":"<grammar>"} — main/<item|equip|magic|status|exit>[/charN][/weapon|armor][/0-3] or shop/<buy|sell|exit>[/N][/charN]

            Decide from the SCREENSHOT what's happening. The plan is a hint; if the
            screen shows something else (e.g. dialog, menu, encounter), handle that
            FIRST. For navigation, emit a short button sequence — you'll see the
            result next turn and can adapt.

            JSON only.
        """.trimIndent()
    }

    private fun parseToolDecision(raw: String): Triple<String, Map<String, String>, String> {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in llm response: ${raw.take(200)}" }
        val jsonText = raw.substring(start, end + 1)
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ToolWire.serializer(), jsonText)
        val reasoning = "llm: ${parsed.reasoning?.take(80) ?: ""}"
        if (!parsed.sequence.isNullOrEmpty()) {
            // Encode the sequence list as the args map under "buttons" key so
            // dispatch() can recover it without changing its signature.
            return Triple("sequence", mapOf("buttons" to parsed.sequence.joinToString(",")), reasoning)
        }
        return Triple(parsed.tool ?: "(none)", parsed.args ?: emptyMap(), reasoning)
    }

    private suspend fun dispatch(tool: String, args: Map<String, String>): ToolOutcome = when (tool) {
        "boot"            -> tools.boot()
        "walkTo"          -> tools.walkTo(args.getValue("x").toInt(), args.getValue("y").toInt())
        "interactAt"      -> tools.interactAt(args.getValue("x").toInt(), args.getValue("y").toInt())
        "useMenu"         -> tools.useMenu(args.getValue("path"))
        "buyAtShop"       -> tools.buyAtShop(
            args.getValue("items").split(",").map { it.toInt() },
            args.getValue("charSlots").split(",").map { it.toInt() },
        )
        "equipWeapon"     -> tools.equipWeapon(args.getValue("charSlot").toInt(), args.getValue("weaponSlot").toInt())
        "restAtInn"       -> tools.restAtInn(args.getValue("innMapId"))
        "battleFightAll"  -> tools.battleFightAll()
        "approachSprite"  -> tools.approachSprite(args.getValue("kind"))
        "sequence"        -> tools.sequence(args.getValue("buttons").split(",").map { it.trim() })
        else              -> ToolOutcome.Reject("unknown tool: $tool")
    }

    private fun advancePlan(plan: Plan) {
        val advanced = plan.copy(cursor = (plan.cursor + 1).coerceAtMost(plan.steps.size))
        memory.setPlan(advanced)
    }

    @kotlinx.serialization.Serializable
    private data class ToolWire(
        val tool: String? = null,
        val args: Map<String, String>? = null,
        val reasoning: String? = null,
        val sequence: List<String>? = null,
    )

    companion object {
        private val EXECUTOR_SYSTEM_PROMPT = """
            You are the per-turn tool picker for an FF1 NES playing agent. You receive
            BOTH the current screenshot AND a Haiku-generated scene digest (text).
            Use the SCREENSHOT as your primary source of truth — Haiku's digest is a
            hint that may mis-classify NPCs (it sometimes labels shopkeepers as
            "generic-npc") and the plan from the Advisor was authored on a STALE
            screenshot many turns ago.

            Critical FF1 NES facts:
              - Shops are usually INSIDE specific buildings (weapon/armor/magic/inn).
                You enter by stepping onto a door tile (mapflags.bit1 transients).
                Once inside, mapId>0 and the shopkeeper stands behind a counter.
              - Coneria CASTLE is just NW of Coneria TOWN on the overworld and has its
                own entrance — DO NOT walk into the castle by mistake.
              - walkTo args depend on phase: Overworld→world coords (80-240),
                Town→town-local (0-31), Indoors→ignored (acts as exitInterior).

            Decision discipline:
              - Compare the plan's next step against what you SEE. If the step's
                coords look wrong for current phase, pick the right tool from current
                phase instead.
              - If you see a building door / arch that leads to a shop you need,
                walk onto it.
              - If the screenshot shows a dialog overlay, advance it (A or B taps)
                instead of trying to walk.

            WrongClass discipline: if buyAtShop returns FAIL "WrongClass: char=N
            itemSlot=K", the item is incompatible with THAT character, not bad
            in general. On retry, swap the SAME item to a different char that
            still has no weapon — don't abandon the item.

            Coneria weapon shop layout (memorise — shop slots 0..4):
              0=Wooden Staff (BlackMage/WhiteMage/RedMage/BlackBelt)
              1=Small Knife  (Fighter/Thief/BlackMage/RedMage)
              2=Wooden Nunchucks (BlackBelt ONLY)
              3=Rapier       (Fighter/Thief/RedMage)
              4=Iron Hammer  (Fighter/WhiteMage/RedMage)
            Default party charSlot 0=Fighter, 1=Thief, 2=BlackBelt, 3=RedMage.
            Recommended pairs: (Fighter,4=Hammer), (Thief,3=Rapier),
            (BlackBelt,2=Nunchucks), (RedMage,3=Rapier).

            Anti-oscillation discipline (CRITICAL):
              - Read the "Recent moves" block. Each entry shows pre-sm → tool →
                post-sm and whether the party MOVED.
              - If the party did NOT MOVE despite tapping a cardinal: that direction
                is a WALL or NPC. DO NOT repeat the same tap.
              - If party sm has not changed for 3+ recent turns: take a LONG DETOUR
                via the perpendicular axis (e.g. if Left+Up are blocked, try Down
                4-5 tiles, then Left, then Up around the obstacle). Buildings have
                exactly ONE door — usually on the south or east face — walk all the
                way around if needed.
              - When near a building you want to enter, look for a tile with a
                visible doorway/arch (darker rectangle in the wall). Walk ONTO that
                exact tile to trigger entry.

            Respond with JSON only.
        """.trimIndent()
    }
}
