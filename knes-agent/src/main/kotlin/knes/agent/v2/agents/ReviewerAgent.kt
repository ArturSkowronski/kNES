package knes.agent.v2.agents

import knes.agent.v2.llm.HaikuClient
import knes.agent.v2.runtime.ReviewEntry
import knes.agent.v2.runtime.V2Memory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

class ReviewerAgent(
    private val haiku: HaikuClient,
    private val memory: V2Memory,
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
    suspend fun audit(turn: Int) {
        val memoryDigest = digestMemory()
        val prompt = """
            You are the FF1 Memory auditor. Identify issues with the following game memory.
            Return ONLY JSON: {"actions":[{"kind":"remove_stale|flag_for_cartographer","entry":"...","reason":"..."}]}

            Memory digest:
            $memoryDigest

            Rules:
            - remove_stale: entry hasn't been referenced in plans/recent decisions OR is older than 50 turns OR contradicts ground truth
            - flag_for_cartographer: inconsistency between two ground-truth artifacts (e.g. blockage at a tile that terrain says walkable)
            - Never invent entries. Only reference what's in the digest.
            - Empty actions list is fine if Memory is clean.
        """.trimIndent()

        // For first cut, use a simple chat invocation through v1 AnthropicSession
        // (HaikuClient currently only holds the modelId; full integration in followup task)
        val response = invokeHaiku(prompt)
        val parsed = json.decodeFromString(AuditWire.serializer(), extractJson(response))

        val removed = mutableListOf<String>()
        val flagged = mutableListOf<String>()
        for (a in parsed.actions) {
            val line = json.encodeToString(AuditAction.serializer(),
                AuditAction(turn = turn, kind = a.kind, entry = a.entry, reason = a.reason))
            memory.appendReviewLine(line)
            when (a.kind) {
                "remove_stale" -> { applyRemove(a.entry); removed += a.entry }
                "flag_for_cartographer" -> { appendCartographerFlag(a.entry, a.reason); flagged += a.entry }
                else -> System.err.println("[v2.reviewer] unknown action kind: ${a.kind}")
            }
        }

        memory.campaign.reviews += ReviewEntry(turn = turn, removed = removed, flagged = flagged)
        memory.saveCampaign()
        System.err.println("[v2.reviewer] turn=$turn removed=${removed.size} flagged=${flagged.size}")
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
        val existing = if (path.toFile().exists()) Files.readString(path) else "[]"
        val list = json.parseToJsonElement(existing).toString().trimEnd(']') +
                   (if (existing.trim() == "[]") "" else ",") +
                   """{"entry":"$entry","reason":"$reason"}]"""
        Files.writeString(path, list)
    }

    private suspend fun invokeHaiku(prompt: String): String {
        // Placeholder — Wire AnthropicSession.send with model=haiku in Task D2.
        return """{"actions":[]}"""
    }

    private fun extractJson(raw: String): String =
        raw.substringAfter("{").let { "{$it" }.substringBeforeLast("}") + "}"

    @Serializable
    private data class AuditWire(val actions: List<AuditAction>)
    @Serializable
    private data class AuditAction(
        val turn: Int = 0,
        val kind: String,
        val entry: String,
        val reason: String,
    )
}
