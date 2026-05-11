package knes.agent.save

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.agent.runtime.AgentScratchpad
import knes.agent.tools.save.DecisionEntry
import knes.agent.tools.save.MoveEntry

class ScratchpadProjectionTest : FunSpec({

    test("recentMoves filters to tap and walk, returns last N in order") {
        val pad = AgentScratchpad.newSession()
        repeat(12) { i ->
            pad.record(
                kind = "tap",
                summary = "step $i",
                dir = "DOWN",
                smPre = i to 0,
                smPost = (i + 1) to 0,
            )
        }
        pad.record(kind = "phase", summary = "shop_reached")
        pad.record(kind = "decision", summary = "buy: pick char3 — cheapest")

        val moves: List<MoveEntry> = pad.recentMoves(n = 5)
        moves.size shouldBe 5
        moves.map { it.smPre!![0] } shouldBe listOf(7, 8, 9, 10, 11)
        moves[0].dir shouldBe "DOWN"
    }

    test("recentDecisions filters to decision kind, returns last M") {
        val pad = AgentScratchpad.newSession()
        pad.record(kind = "tap", summary = "x", dir = "UP")
        repeat(40) { i ->
            pad.recordDecision(phase = "buy", reasoning = "r$i", action = "a$i")
        }
        val decisions: List<DecisionEntry> = pad.recentDecisions(n = 30)
        decisions.size shouldBe 30
        decisions.first().phase shouldBe "buy"
        decisions.first().reasoning shouldBe "r10"
        decisions.last().reasoning shouldBe "r39"
    }

    test("recordDecision appends with kind=decision") {
        val pad = AgentScratchpad.newSession()
        pad.recordDecision(phase = "exit", reasoning = "south door", action = "tap DOWN")
        val all = pad.all()
        all.size shouldBe 1
        all[0].kind shouldBe "decision"
    }
})
