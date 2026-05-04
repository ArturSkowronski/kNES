package knes.agent.executor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import knes.agent.advisor.AdvisorAgent

/**
 * Verifies that ExecutorAgent.GOAL_PARAGRAPH and AdvisorAgent.GOAL_PARAGRAPH
 * each match a substring of their respective system prompts verbatim, and that
 * string-replace cleanly swaps without leftover Garland references.
 */
class GoalOverrideTest : FunSpec({
    test("ExecutorAgent: GOAL_PARAGRAPH appears verbatim in system prompt") {
        ExecutorAgent.ff1ExecutorSystemPrompt shouldContain ExecutorAgent.GOAL_PARAGRAPH
    }

    test("ExecutorAgent: replacement removes Garland references") {
        val newGoal = "- Goal: enter Coneria Town interior (mapId=8)."
        val swapped = ExecutorAgent.ff1ExecutorSystemPrompt.replace(
            ExecutorAgent.GOAL_PARAGRAPH, newGoal
        )
        swapped shouldContain newGoal
        swapped.shouldNotContain("AtGarlandBattle = Battle.enemyId")
    }

    test("AdvisorAgent: GOAL_PARAGRAPH appears verbatim in system prompt") {
        AdvisorAgent.systemPrompt shouldContain AdvisorAgent.GOAL_PARAGRAPH
    }

    test("AdvisorAgent: replacement removes Garland references") {
        val newGoal = "- Goal: enter Coneria Town interior (mapId=8)."
        val swapped = AdvisorAgent.systemPrompt.replace(
            AdvisorAgent.GOAL_PARAGRAPH, newGoal
        )
        swapped shouldContain newGoal
        swapped.shouldNotContain("AtGarlandBattle = Battle.enemyId")
    }
})
