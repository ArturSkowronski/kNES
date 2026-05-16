package knes.agent.agents

import knes.agent.llm.AnthropicSession
import knes.agent.llm.GeminiPro31Client
import knes.agent.llm.HaikuClient
import knes.agent.llm.SonnetClient
import knes.agent.runtime.MilestonePredicates
import knes.agent.runtime.Plan
import knes.agent.runtime.PlanStep
import knes.agent.runtime.Memory
import knes.agent.runtime.RunDirectory
import knes.agent.tools.ToolOutcome
import knes.agent.tools.ToolSurface

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
    private val memory: Memory,
    private val run: RunDirectory? = null,
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

        // Only advance the plan cursor when the EXECUTED tool matches the
        // tool the current plan step intended. Previously any Ok outcome
        // advanced the cursor — so 6 successful `sequence(Right)` taps could
        // burn through a 6-step plan in 6 turns without the agent ever
        // reaching the shop counter. After that the Executor had no plan
        // anchor and started zig-zagging based purely on Flash-Lite's
        // per-turn screen reading.
        if (outcome is ToolOutcome.Ok && plan != null) {
            val currentStep = plan.steps.getOrNull(plan.cursor)
            val intent = currentStep?.intentTool
            // If the plan step declared its intent tool, require it to
            // match before advancing. If intentTool is null (older plans),
            // fall back to the legacy "any Ok advances" behaviour.
            if (intent == null || intent == tool) {
                advancePlan(plan)
            }
        }

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
            knes.agent.runtime.Log.error("askLlm error: ${e.message?.take(160)} — falling back to plan tail")
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
                {"tool":"<one of: restAtInn|useMenu>","args":{...},"reasoning":"..."}
                  restAtInn:    args = {"innMapId":"<int>"}
                  useMenu:      args = {"path":"<grammar>"} — main/<item|equip|magic|status|exit>[/charN][/weapon|armor][/0-3] or shop/<buy|sell|exit>[/N][/charN]

                NOTE: buyAtShop and equipWeapon are REMOVED — their cursor
                state machines kept misfiring. Shopping AND equipping are
                done via raw `sequence` taps: see the SHOPPING PLAYBOOK
                and EQUIPPING PLAYBOOK sections below.

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
        // buyAtShop removed — its cursor state machine kept returning
        // WrongClass for valid pairs. Shopping is now done via raw
        // `sequence` taps (see SHOPPING PLAYBOOK in the Executor prompt).
        "buyAtShop"       -> ToolOutcome.Reject(
            "buyAtShop is disabled — buy weapons via raw `sequence` taps. " +
            "Walk adjacent-south of the weapon shopkeeper, face N, then: " +
            "A (talk) → A (open menu) → Up/Down to highlight item → A (select) " +
            "→ Up/Down to pick char → A (confirm) → A (Yes). Read screen each step."
        )
        // equipWeapon removed — its cursor state machine assumed a fresh
        // main menu each call; chaining 4 equips never worked reliably.
        // Equipping is now done via raw `sequence` taps (see EQUIPPING
        // PLAYBOOK in the Executor prompt).
        "equipWeapon"     -> ToolOutcome.Reject(
            "equipWeapon is disabled — equip via raw `sequence` taps. " +
            "From the field: START to open main menu → Down to highlight " +
            "Equip → A → A to pick char1 (or Down/Up to pick another) → " +
            "Right to switch to Weapon panel → Down to highlight a held " +
            "weapon → A to equip. The bit7 of the byte flips on success."
        )
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
            You are the per-turn tool picker for an FF1 NES playing agent.
            You see the current screenshot + a RAM digest. The screenshot is
            the source of truth; the Advisor's plan is a hint authored on a
            stale screenshot and may be wrong about the current scene.

            === FF1 NES FACTS ===
            - Coneria/Pravoka SHOPS AND INNS are NPC-dialog OVERLAYS over
              the town overlay. RAM stays at mapId=0 mapflags=1 — IDENTICAL
              to walking around town. Distinguish via the SCREENSHOT (sign
              text: "WEAPON" / "INN" / "ARMOR"; counter contents: sword/
              club sprite vs. bed sprite), not RAM.
            - Coneria CASTLE is NW of Coneria TOWN on the overworld with
              its own entry. Do not walk in by mistake.
            - walkTo arg coord-space: Overworld→world (80-240),
              Town→town-local (0-31), Indoors→args ignored (acts as exit).

            === VIEWPORT SPATIAL READING (do FIRST every turn) ===
            Viewport ~16×14 tiles, party at centre (~tile 8,7). Before
            choosing a button, mentally answer:
              a) What tile is DIRECTLY N/S/E/W of the party? (path / grass
                 / wall / water / NPC / building-wall / building-door /
                 counter)
              b) Where on screen are the visible BUILDINGS, and what does
                 each sign read? Use quadrants (top-left … bottom-right).
              c) Where does the PATH from the party tile branch? List each
                 branch by cardinal direction.
            Do NOT say "near the Inn" when the Inn is several tiles away
            in another quadrant. "Near" only counts when the party tile is
            orthogonally adjacent to a building wall/door.

            === PATH-FOLLOWING + DEAD-END RECOVERY ===
            - FF1 towns are designed around visible PATHS (light paved
              tiles). All shops connect to the main path network — follow
              the path to its forks and try each branch and you will reach
              every shop. Grass between buildings is an obstacle, not a
              detour.
            - PRIMARY RULE: prefer cardinal moves that keep the party on a
              path tile. Stepping onto grass is allowed only to round one
              blocking NPC — return to the path next tap.
            - If you find yourself on grass or against a wall and the path
              is one tile to the side, step BACK to the path first.
            - DEAD-END: if you tap the SAME cardinal 3+ times without the
              sm coord changing AND a building wall is the obstacle, you
              are on a DEAD-END BRANCH that terminates at THAT building.
              Recovery: tap the OPPOSITE cardinal once or twice to back
              onto the main trunk, then take a DIFFERENT branch at the
              nearest fork.
            - Coneria trap: a short north-pointing stub ends at the INN
              door. From that dead-end, back SOUTH two tiles to the trunk,
              then continue N — the WEAPON shop is via a different branch.

            === ACCIDENTAL-DIALOG TRAP ===
            If the screenshot shows a Yes/No prompt or speech-bubble window
            AND the current plan step is NOT a shopping/inn step → emit
            {"sequence":["B"],"reasoning":"cancel accidental NPC dialog"}.
            Never press A on an unverified Yes/No — each accidental Yes at
            the Coneria INN burns 30G. Visual tell INN vs WEAPON: INN room
            has a small bed sprite (blue rectangle) + purple "INN" sign;
            WEAPON shop has a sword/club on the counter + "WEAPON" sign.

            === SHOPPING PLAYBOOK (raw `sequence` taps) ===
            Coneria weapon shop, ~8-12 single-button taps per weapon. Read
            the screenshot after each tap; menu state dictates next button.
              1. Stand on the tile directly SOUTH of the shopkeeper,
                 facing N.
              2. A → "Welcome!" dialog.
              3. A → item list (Staff/Knife/Nunchucks/Rapier/Hammer + prices).
              4. Down×N to highlight desired item; A → "Which one?" then A
                 → "Whom?" prompt.
              5. Down×N to highlight target char; A.
              6. "Yes/No?" cursor on Yes → A. Gold drops, weapon enters
                 char's first free weapon slot.
              7. Repeat from step 4 for next weapon, or B+B to leave.
            Coneria item indices (cursor 0..4, top-to-bottom):
              0 Wooden Staff   5G  → BlackMage/WhiteMage/RedMage/BlackBelt
              1 Small Knife    5G  → Fighter/Thief/BlackMage/RedMage
              2 Wooden Nunchucks 10G → BlackBelt ONLY
              3 Rapier        10G  → Fighter/Thief/RedMage
              4 Iron Hammer   10G  → Fighter/WhiteMage/RedMage
            Default party charSlot 0=Fighter, 1=Thief, 2=BlackBelt, 3=RedMage.

            *** SHOPPING LIST — buy a DIVERSE set so the equip phase
            doesn't deadlock on class mismatch ***
            MINIMUM viable purchase (40G total, well under starting 400G):
              slot 2 (Nunchucks)  × 1  — MANDATORY for BlackBelt; without
                                         this the BlackBelt has nothing
                                         to equip and arm_party stalls
              slot 3 (Rapier)     × 2  — Fighter + Thief + RedMage covered
              slot 4 (Iron Hammer)× 1  — Fighter or RedMage primary

            NEVER buy 2 copies of the SAME narrow-class weapon. Two
            Knives leaves BlackBelt blocked. Two Hammers leaves Thief
            blocked. ALWAYS include the Nunchucks for BlackBelt.

            Recommended per-char assignment after the buy:
              Fighter   → Iron Hammer
              Thief     → Rapier
              BlackBelt → Nunchucks  (only option besides Staff)
              RedMage   → Rapier

            If the Yes/No is rejected ("Cannot equip!"): WrongClass for
            that pair — B to back out, retry the SAME item with a
            different char that still has no weapon. Don't abandon items.

            === EQUIPPING PLAYBOOK — FAST PATH (memorize the EXACT sequence) ===
            From FIELD to one weapon equipped (only ~5 taps if done right):

                START   →   (cursor to WEAPON)   →   A
                  →   (cursor RIGHT to EQUIP)   →   A
                  →   (Up/Down to a held weapon row)   →   A
                  →   weapon's bit7 flips, "E" appears

            That is the ENTIRE flow. No character-select dialog appears in
            between EQUIP and the weapon list — pressing A on EQUIP drops
            the cursor straight onto the per-char weapon rows shown below
            the sub-action header. Each row is one character's currently-
            held weapon. You pick a ROW (which means you pick both the
            character AND the weapon at once), press A, and bit7 flips IF
            the character's class can equip that weapon. If the class
            can't equip it, NOTHING happens (no bit7 flip, no error) — see
            CLASS-FIT below.

            === STATE MACHINE (which screen am I on?) ===
            Read the SCREENSHOT and self-classify into one of these states
            BEFORE choosing the next button:

              FIELD — party visible on map tiles, no menu box.
                next: START to open main menu.

              MAIN_MENU — small box on the right with options WEAPON,
                ARMOR, MAGIC, ITEM, STATUS (vertical list). Hand cursor on
                one of them.
                next: Up/Down to highlight WEAPON, then A.

              WEAPON_SUB_HEADER — full-screen layout with header
                `WEAPON | EQUIP | TRADE | DROP` at the top AND the
                4-character weapon list below. Hand cursor is in the
                HEADER (pointing at one of the four sub-action words).
                The currently selected sub-action is the inverted/lit one.
                next: Right/Left until EQUIP is highlighted, then A.

              WEAPON_LIST — same screen as WEAPON_SUB_HEADER but the hand
                cursor has moved DOWN to one of the character rows (it
                points at a "Wooden ..." or similar weapon name).
                next: Up/Down to a held weapon for a class that can equip
                it (see CLASS-FIT), then A.

              EQUIPPED — same screen, but next turn's partyWeaponDigest
                shows `idx*` for that char's slot.
                next: B once to return to WEAPON_SUB_HEADER, pick next
                char's weapon. When all done: B → B → B → FIELD.

            CRITICAL: when you A on a CHARACTER ROW while still in
            WEAPON_SUB_HEADER cursor mode (header cursor on WEAPON, not
            EQUIP), the game enters the WEAPON viewer (info popup), NOT
            the equip flow — bit7 will NOT flip. Always verify EQUIP is
            the highlighted sub-action before pressing A.

            === CLASS-FIT (silent no-op trap) ===
            FF1 equip silently fails if the character's class can't use
            the weapon. Coneria weapons → classes that can equip:
              Wooden Staff      → BlackMage / WhiteMage / RedMage / BlackBelt
              Small Knife       → Fighter / Thief / BlackMage / RedMage
              Wooden Nunchucks  → BlackBelt ONLY
              Rapier            → Fighter / Thief / RedMage
              Iron Hammer       → Fighter / WhiteMage / RedMage
            Default party (charSlot → class):
              0 Fighter, 1 Thief, 2 BlackBelt, 3 RedMage.
            So Rapier on Fighter/Thief/RedMage = ok. Nunchucks ONLY on
            BlackBelt. If A on a row didn't flip bit7 next turn → class
            mismatch — B back to header, pick a DIFFERENT char's row.

            === RAM SELF-CHECK (after every A in WEAPON_LIST state) ===
            Look at the partyWeaponDigest line in the next turn's prompt.
            For the char/slot you just pressed A on:
              `c2:1*`  → SUCCESS. bit7 set. Move on to next char.
              `c2:1`   → NO-OP. Either wrong sub-action (still in viewer)
                         or class mismatch. Do NOT press A again on the
                         same row — B back out and verify EQUIP is the
                         highlighted sub-action OR pick a different char.

            === PITFALLS ===
            - Screenshot shows field/world map (no menu visible): you
              accidentally exited. Press START to reopen the main menu.
            - Cursor wraps: Up at top of a list lands at bottom; Right at
              end of the sub-action header lands at the start.
            - "Wooden-XYZ" next to a char in the WEAPON sub-screen means
              they HOLD it. Held ≠ equipped. Only the `E` marker (or
              bit7 in RAM) confirms equipped.

            === ANTI-OSCILLATION ===
            Read the "Recent moves" block. Each entry shows pre-sm → tool →
            post-sm with MOVED/NO-MOVEMENT.
            - NO-MOVEMENT on a cardinal = wall or NPC blocking. Do not
              repeat the same tap.
            - If sm has not changed for 3+ recent turns: take a LONG
              perpendicular detour (e.g. if Up+Left are blocked, go Down
              4-5 tiles, then Left, then Up around the obstacle).
              Buildings have exactly ONE door (usually south or east face)
              — walk all the way around if needed.
            - To ENTER a building: find the tile with a visible
              doorway/arch (darker rectangle in the wall) and walk ONTO
              that exact tile.

            Respond with JSON only.
        """.trimIndent()
    }
}
