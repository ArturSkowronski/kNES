package knes.agent.v2.agents

import knes.agent.v2.llm.GeminiPro31Client
import knes.agent.v2.runtime.Plan
import knes.agent.v2.runtime.PlanEntry
import knes.agent.v2.runtime.PlanStep
import knes.agent.v2.runtime.V2Memory
import knes.agent.v2.runtime.V2RunDirectory

/**
 * Advisor writes a numbered plan into current_plan.json and appends a
 * PlanEntry to campaign.json. Called on T=0, stuck-signal, and phase
 * boundaries. ~2% of total LLM calls.
 */
class AdvisorAgent(
    private val gemini: GeminiPro31Client,
    private val memory: V2Memory,
    private val run: V2RunDirectory,
) {
    suspend fun plan(reason: String, screenshotB64: String, turn: Int) {
        val prompt = buildPrompt(reason)
        val raw = gemini.generate(prompt, imageB64 = screenshotB64)
        val plan = parsePlan(raw, milestone = currentMilestone(), turn = turn)
        memory.setPlan(plan)
        memory.campaign.plans += PlanEntry(
            turn = turn, by = "advisor",
            summary = plan.steps.firstOrNull()?.description ?: "(no steps)",
            snapshot = "snapshots/turn-%05d.png".format(turn),
            reason = reason,
        )
        memory.saveCampaign()
        System.err.println("[v2.advisor] turn=$turn reason=$reason steps=${plan.steps.size}")
    }

    private fun currentMilestone(): String =
        memory.campaign.milestones.firstOrNull { it.status == "in_progress" }?.id
            ?: memory.campaign.milestones.firstOrNull { it.status == "pending" }?.id
            ?: "boot"

    private fun buildPrompt(reason: String): String = """
        You are the FF1 strategic advisor. Produce a NUMBERED plan (max 8 steps)
        to advance the current milestone. Respond ONLY with JSON.

        Current milestone: ${currentMilestone()}
        Trigger reason: $reason
        Campaign so far: ${memory.campaign.milestones.joinToString { "${it.id}=${it.status}" }}
        Recent plans: ${memory.campaign.plans.takeLast(3).joinToString("\n") { "T${it.turn}: ${it.summary}" }}

        Output schema:
        {"steps":[
          {"index":0,"description":"...","intentTool":"walkTo|interactAt|useMenu|buyAtShop|equipWeapon|restAtInn|battleFightAll","intentArgs":{"x":"15","y":"22"}},
          ...
        ]}

        Available tools: walkTo(x,y), interactAt(x,y), useMenu(path), buyAtShop(items,charSlots),
        equipWeapon(charSlot,weaponSlot), restAtInn(innMapId), battleFightAll().
    """.trimIndent()

    private fun parsePlan(raw: String, milestone: String, turn: Int): Plan {
        // Gemini often wraps JSON in ```json ... ``` fences or includes preamble text.
        // Robust extraction: strip fences, then take substring between first `{` and last `}`.
        val stripped = raw
            .replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
        val firstBrace = stripped.indexOf('{')
        val lastBrace = stripped.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace < firstBrace) {
            System.err.println("[v2.advisor] no JSON braces in response (first 300 chars): ${raw.take(300)}")
            throw IllegalStateException("Advisor: no JSON object in Gemini response")
        }
        val jsonText = stripped.substring(firstBrace, lastBrace + 1)
        val parsed = try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString(PlanWire.serializer(), jsonText)
        } catch (e: Exception) {
            System.err.println("[v2.advisor] JSON parse failed; extracted=${jsonText.take(500)}")
            throw e
        }
        return Plan(
            createdAtTurn = turn,
            milestone = milestone,
            steps = parsed.steps.mapIndexed { i, s ->
                PlanStep(i, s.description, s.intentTool, s.intentArgs)
            },
        )
    }

    @kotlinx.serialization.Serializable
    private data class PlanWire(val steps: List<StepWire>)
    @kotlinx.serialization.Serializable
    private data class StepWire(
        val description: String,
        val intentTool: String? = null,
        val intentArgs: Map<String, String>? = null,
    )
}
