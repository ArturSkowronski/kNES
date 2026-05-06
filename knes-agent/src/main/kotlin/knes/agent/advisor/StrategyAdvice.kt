package knes.agent.advisor

import knes.agent.runtime.RecentDecisionsBuffer
import knes.agent.runtime.StrategicDecision
import knes.agent.runtime.StrategyContext

/**
 * Pure functions composing the strategy advisor prompt and post-processing
 * its output. Kept separate from [AdvisorAgent] (Koog AIAgent infra) so the
 * prompt + parser logic is unit-testable without spinning up an LLM.
 */
object StrategyAdvice {

    fun buildPrompt(
        ram: Map<String, Int>,
        recent: RecentDecisionsBuffer,
        innTile: Pair<Int, Int>,
        bridgeTile: Pair<Int, Int>,
        targetMinLevel: Int,
    ): String {
        val ramLine = StrategyContext.summarize(ram, innTile, bridgeTile)
        val recentText = recent.lastN(3).joinToString(", ")
        return buildString {
            appendLine(ramLine)
            appendLine("recent: [$recentText]")
            appendLine("target: min_level >= $targetMinLevel before BRIDGE")
            append("Reply with EXACTLY ONE token: GRIND or REST or BRIDGE.")
        }
    }

    /**
     * Sanity guards applied after parsing the advisor reply:
     *   - thrashing → force GRIND
     *   - premature BRIDGE (min_level < 2) → force GRIND
     */
    fun applySanityGuards(
        decision: StrategicDecision,
        ram: Map<String, Int>,
        isThrashing: Boolean,
    ): StrategicDecision {
        if (isThrashing) return StrategicDecision.GRIND
        // L0/L1 can never cross safely; L2+ deferred to advisor judgement
        if (decision == StrategicDecision.BRIDGE && StrategyContext.minLevel(ram) < 2) {
            return StrategicDecision.GRIND
        }
        return decision
    }

    /** System prompt for the strategy-advisor Koog agent. */
    const val SYSTEM_PROMPT = """
You are a strategic FF1 advisor for an automated agent grinding XP near
the Coneria spawn before attempting to cross the north bridge to Garland.

Your job: read the party stats and recent decisions, then reply with EXACTLY
ONE token from {GRIND, REST, BRIDGE}. No reasoning, no commentary, just the token.

- GRIND  = continue walking the spawn corridor to trigger random encounters.
- REST   = travel to the Coneria inn, pay for a heal, return.
- BRIDGE = commit to bridge crossing (irreversible — only when min_level >= target).
"""
}
