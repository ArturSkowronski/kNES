package knes.agent.v2.agents

import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.runtime.MilestonePredicates
import knes.agent.v2.runtime.Phase
import knes.agent.v2.runtime.ReviewEntry
import knes.agent.v2.runtime.V2Memory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

class ReviewerAgent(
    private val haiku: HaikuClient,
    private val memory: V2Memory,
    private val run: knes.agent.v2.runtime.V2RunDirectory? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        /** A milestone in_progress longer than this triggers a stuck-progress replan. */
        private const val STUCK_THRESHOLD_TURNS = 60
        /** Don't replan more often than every N turns even if still stuck. */
        private const val STUCK_REPLAN_COOLDOWN = 30
    }

    /**
     * Read entire Memory (Campaign + on-disk landmarks/warps/blockages),
     * ask Haiku for audit issues, parse JSON, apply REMOVE actions, append
     * FLAG actions to cartographer-flags.json.
     *
     * Safety:
     *   - Every action appended to review.jsonl BEFORE mutation.
     *   - Only `remove_stale` mutates Memory files.
     *   - `flag_for_cartographer` only writes to cartographer-flags.json.
     */
    /**
     * LLM-driven plan audit. Asks Haiku to compare the Advisor's plan against
     * the live RAM + screenshot and list discrepancies between claimed and
     * actual state (e.g. "Equipped Rapier for char1" while char1_weapon0 has
     * bit7 unset). Returns list of human-readable issues; caller decides
     * whether to trigger replan.
     */
    suspend fun auditPlan(turn: Int, screenshotB64: String, ramDigest: String): List<String> {
        run?.markActive("reviewer", turn)
        val plan = memory.currentPlan
        if (plan == null) { run?.markIdle(); return emptyList() }
        val planDesc = buildString {
            append("milestone=${plan.milestone} cursor=${plan.cursor}/${plan.steps.size}\n")
            for (s in plan.steps) {
                val tag = when {
                    s.index < plan.cursor -> "DONE"
                    s.index == plan.cursor -> "CURRENT"
                    else -> "TODO"
                }
                append("  [${s.index}] $tag ${s.intentTool}(${s.intentArgs ?: emptyMap()}) — ${s.description}\n")
            }
        }
        val raw = try {
            haiku.auditPlan(planDesc, ramDigest, screenshotB64)
        } catch (e: Exception) {
            knes.agent.v2.runtime.Log.error("auditPlan LLM error: ${e.message?.take(120)}", turn)
            run?.markIdle()
            return emptyList()
        }
        runCatching {
            run?.promptFile(turn, "reviewer")?.toFile()?.writeText(
                "=== PLAN ===\n$planDesc\n\n=== RAM ===\n$ramDigest\n\n=== HAIKU RESPONSE ===\n$raw"
            )
        }
        val parsed = try {
            json.decodeFromString(IssuesWire.serializer(), extractJson(raw))
        } catch (e: Exception) {
            knes.agent.v2.runtime.Log.error("auditPlan parse error: ${e.message?.take(80)} raw=${raw.take(120)}", turn)
            run?.markIdle()
            return emptyList()
        }
        val issues = parsed.issues.map { "step=${it.step}: ${it.problem.take(80)}" }
        if (issues.isNotEmpty()) {
            knes.agent.v2.runtime.Log.reviewer("AUDIT found ${issues.size} issues: $issues", turn)
            for (issue in issues) memory.appendReviewLine(
                json.encodeToString(AuditAction.serializer(),
                    AuditAction(turn = turn, kind = "plan_issue", entry = issue, reason = "haiku audit"))
            )
            memory.campaign.reviews += ReviewEntry(turn = turn, removed = emptyList(), flagged = issues)
            memory.saveCampaign()
        }
        run?.markIdle()
        return issues
    }

    /**
     * Legacy memory audit (still no-op LLM — placeholder retained for older
     * call sites). Routes through markActive so the UI still highlights when
     * called.
     */
    suspend fun audit(turn: Int) {
        // Memory audit not yet wired to a real LLM call (different scope from
        // plan audit). For now this is a no-op shell that just bumps the
        // active-agent indicator and writes a stub review entry.
        run?.markActive("reviewer", turn)
        memory.campaign.reviews += ReviewEntry(turn = turn, removed = emptyList(), flagged = emptyList())
        memory.saveCampaign()
        knes.agent.v2.runtime.Log.reviewer("memory-audit (placeholder, no LLM)", turn)
        run?.markIdle()
    }

    /**
     * Deterministic milestone verifier — re-evaluates each "done" milestone
     * predicate against current RAM. If a previously-latched milestone no
     * longer holds, revert it to "in_progress" (caller should trigger Advisor
     * replan). Returns the list of regressed milestone IDs.
     *
     * Catches: savestate restore that drops items/levels; falsely-latched
     * milestone that fired on transient signal (mapflags transient, etc.).
     */
    fun verifyMilestones(phase: Phase, ram: Map<String, Int>, turn: Int): List<String> {
        run?.markActive("reviewer", turn)
        val ms = memory.campaign.milestones
        val prereqDone = ms.associate { it.id to (it.status == "done") }
        val regressed = mutableListOf<String>()
        val checked = mutableListOf<String>()
        for (m in ms) {
            if (m.status != "done") continue
            // Event-type milestones describe a transient state — don't
            // re-verify or we'd regress a legitimate latch.
            if (m.id in MilestonePredicates.EVENT_TYPE) continue
            checked += m.id
            if (!MilestonePredicates.evaluate(m.id, phase, ram, prereqDone)) {
                knes.agent.v2.runtime.Log.warn("REGRESSION milestone ${m.id} no longer satisfied — reverting to in_progress", turn)
                m.status = "in_progress"
                m.turnEnd = null
                regressed += m.id
            }
        }
        if (regressed.isNotEmpty()) memory.saveCampaign()
        if (checked.isNotEmpty()) {
            knes.agent.v2.runtime.Log.reviewer("verify checked=[${checked.joinToString(",")}] regressed=[${regressed.joinToString(",")}]", turn)
        }
        run?.markIdle()
        return regressed
    }

    /**
     * Stuck-progress detector — third Reviewer pass, deterministic (no LLM).
     *
     * The previous two passes only catch (a) regression of DONE milestones
     * and (b) Haiku-flagged plan/RAM contradictions. Neither notices the
     * pattern where:
     *   - the current IN_PROGRESS milestone has been so for many turns
     *   - the Executor has been spamming taps but the predicate isn't satisfied
     *   - the plan has no further steps to advance the cursor through
     *
     * Example seen during demo (T176): arm_party in_progress since T53 (123
     * turns), 2/4 chars HELD a weapon but 0/4 EQUIPPED — the
     * `armCharsViaMenu` sentinel kept the cursor parked while the Executor
     * tunneled into the Equip menu without successfully flipping any bit7.
     *
     * Detection: in_progress duration ≥ [STUCK_THRESHOLD_TURNS].
     * Action: build a RAM-aware diagnosis string (so the Advisor sees
     * partial-progress numbers like "2/4 held, 0/4 equipped") and return
     * it. Caller (Main) decides what to do — typically triggers a fresh
     * Advisor replan with the diagnosis as reason. Includes a cooldown
     * (lastStuckReplanTurn) so we don't replan-storm.
     */
    data class StuckDiagnostic(val milestone: String, val elapsedTurns: Int, val reason: String)

    private var lastStuckReplanTurn: Int = -1

    fun checkProgress(turn: Int, ram: Map<String, Int>): StuckDiagnostic? {
        val current = memory.campaign.milestones.firstOrNull { it.status == "in_progress" } ?: return null
        val start = current.turnStart ?: return null
        val elapsed = turn - start
        if (elapsed < STUCK_THRESHOLD_TURNS) return null
        // Cooldown — don't replan every 10 turns while stuck.
        if (turn - lastStuckReplanTurn < STUCK_REPLAN_COOLDOWN) return null
        lastStuckReplanTurn = turn

        val party = MilestonePredicates.partyWeaponDigest(ram)
        val held = (1..4).count { MilestonePredicates.charHoldsAny(it, ram) }
        val equipped = (1..4).count { MilestonePredicates.charHasEquipped(it, ram) }
        val gold = ((ram["goldHigh"] ?: 0) shl 16) or
            ((ram["goldMid"] ?: 0) shl 8) or (ram["goldLow"] ?: 0)

        val tail = when (current.id) {
            "arm_party" -> "The current plan's `armCharsViaMenu` sentinel keeps the cursor parked " +
                "while the Executor drives the Equip flow via raw taps — but no weapon byte has flipped " +
                "bit7 in many turns. ROOT CAUSE per FF1 NES manual: there is NO top-level 'Equip' " +
                "command. The correct path is START → WEAPON (top-level) → A → then move cursor in " +
                "the `WEAPON | EQUIP | TRADE | DROP` sub-action header to EQUIP → A → pick char → A → " +
                "pick held weapon → A. The agent likely got stuck pressing A on chars while the " +
                "sub-action cursor was still on WEAPON (the default), which opens the viewer, not the " +
                "equip flow. " +
                "Decide between: (A) RESET — author a fresh plan whose step 0 is `useMenu` with path " +
                "`main/exit` to force-close any open menu, then a step that re-enters via START → " +
                "WEAPON → A → Right (to highlight EQUIP) → A → char → A → weapon-slot → A. " +
                "(B) PROCEED — accept current partial progress, switch focus to the next milestone " +
                "(exit_coneria) by authoring its plan now. Choose (B) if equipped>=1 OR held>=2 " +
                "(party is meaningfully armed; we can finish equipping during grind)."
            "buy_weapons" -> "Buying via raw taps stalled. Try: B×6 to back fully out of any open shop " +
                "dialog, then re-approach the WEAPON shopkeeper from the south and re-open via A."
            "enter_weapon_shop" -> "Reaching sm=(11,11) stalled. Likely dead-end side branch — see " +
                "DEAD-END RECOVERY in the Executor prompt: tap opposite cardinal twice, try a different fork."
            else -> "The milestone predicate is not satisfying. Consider authoring a fresh plan with a " +
                "different approach, or relaxing the goal if some progress has been made."
        }
        val reason = "STUCK on milestone ${current.id} for $elapsed turns (since T$start). " +
            "Live state: $party, $held/4 hold a weapon, $equipped/4 equipped, gold=$gold. $tail"

        knes.agent.v2.runtime.Log.warn("Reviewer.checkProgress: ${current.id} stuck $elapsed turns — triggering replan", turn)
        return StuckDiagnostic(milestone = current.id, elapsedTurns = elapsed, reason = reason)
    }

    private fun digestMemory(): String {
        val campaignJson = Files.readString(memory.run.campaignJson)
        val landmarks = if (memory.run.landmarksJson.toFile().exists()) Files.readString(memory.run.landmarksJson) else "{}"
        val warps = if (memory.run.warpsJson.toFile().exists()) Files.readString(memory.run.warpsJson) else "{}"
        val blockages = if (memory.run.blockagesJson.toFile().exists()) Files.readString(memory.run.blockagesJson) else "{}"
        return "campaign:$campaignJson\nlandmarks:$landmarks\nwarps:$warps\nblockages:$blockages"
    }

    private fun applyRemove(entry: String) {
        // entry format: "landmarks:KEY" | "warps:KEY" | "blockages:(x,y)"
        // For first cut just log — full mutation wired in followup once landmark
        // JSON schemas are stable.
        knes.agent.v2.runtime.Log.reviewer("would remove: $entry")
    }

    private fun appendCartographerFlag(entry: String, reason: String) {
        val path = memory.run.cartographerFlagsJson
        val existing = if (path.toFile().exists()) Files.readString(path).trim() else "[]"
        val updated = if (existing == "[]") {
            """[${json.encodeToString(FlagEntry.serializer(), FlagEntry(entry, reason))}]"""
        } else {
            existing.trimEnd(']') + "," + json.encodeToString(FlagEntry.serializer(), FlagEntry(entry, reason)) + "]"
        }
        Files.writeString(path, updated)
    }

    private suspend fun invokeHaiku(prompt: String): String {
        // Placeholder — wire AnthropicSession.send with model=haiku in followup.
        return """{"actions":[]}"""
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object: ${raw.take(200)}" }
        return raw.substring(start, end + 1)
    }

    @Serializable
    private data class AuditWire(val actions: List<AuditAction>)

    @Serializable
    private data class AuditAction(
        val turn: Int = 0,
        val kind: String,
        val entry: String,
        val reason: String,
    )

    @Serializable
    private data class FlagEntry(val entry: String, val reason: String)

    @Serializable
    private data class IssuesWire(val issues: List<IssueWire> = emptyList())

    @Serializable
    private data class IssueWire(val step: Int = -1, val problem: String = "")
}
