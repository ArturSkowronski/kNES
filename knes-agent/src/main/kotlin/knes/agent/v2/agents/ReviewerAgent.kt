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
            System.err.println("[v2.reviewer] auditPlan LLM error: ${e.message?.take(120)}")
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
            System.err.println("[v2.reviewer] auditPlan parse error: ${e.message?.take(80)} raw=${raw.take(120)}")
            run?.markIdle()
            return emptyList()
        }
        val issues = parsed.issues.map { "step=${it.step}: ${it.problem.take(80)}" }
        if (issues.isNotEmpty()) {
            System.err.println("[v2.reviewer] AUDIT T$turn found ${issues.size} issues: $issues")
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
        System.err.println("[v2.reviewer] memory-audit T$turn (placeholder, no LLM)")
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
            checked += m.id
            if (!MilestonePredicates.evaluate(m.id, phase, ram, prereqDone)) {
                System.err.println("[v2.reviewer] REGRESSION at T$turn: milestone ${m.id} no longer satisfied — reverting to in_progress")
                m.status = "in_progress"
                m.turnEnd = null
                regressed += m.id
            }
        }
        if (regressed.isNotEmpty()) memory.saveCampaign()
        if (checked.isNotEmpty()) {
            System.err.println("[v2.reviewer] verify T$turn: checked=[${checked.joinToString(",")}] regressed=[${regressed.joinToString(",")}]")
        }
        run?.markIdle()
        return regressed
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
        System.err.println("[v2.reviewer] would remove: $entry")
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
