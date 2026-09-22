package knes.agent.decision

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun options(n: Int) = (1..n).map { Option("o$it", "option number $it") }

class DecisionContractTest : FunSpec({

    test("a choice needs at least two options — one option is not a decision") {
        shouldThrow<IllegalArgumentException> {
            Choice("c", "state", "which?", options(1))
        }.message shouldContain "1 options"
    }

    test("a choice stops at sixteen, because the readout has sixteen answer letters") {
        Choice("c", "state", "which?", options(16)).options.size shouldBe 16
        shouldThrow<IllegalArgumentException> { Choice("c", "state", "which?", options(17)) }
    }

    test("repeated option ids are rejected — the winner would be ambiguous") {
        shouldThrow<IllegalArgumentException> {
            Choice("c", "state", "which?", listOf(Option("a", "first"), Option("a", "also first")))
        }.message shouldContain "repeats an option id"
    }

    test("an option the model cannot read is rejected at construction") {
        shouldThrow<IllegalArgumentException> { Option("a", "  ") }
        shouldThrow<IllegalArgumentException> { Option("", "something") }
    }

    test("a choice with nothing to decide about is rejected") {
        shouldThrow<IllegalArgumentException> { Choice("c", "", "which?", options(2)) }
        shouldThrow<IllegalArgumentException> { Choice("c", "state", " ", options(2)) }
    }

    test("Ranking.of sorts best first whatever order the backend used") {
        val ranking = Ranking.of("c", "fake", listOf("a" to 0.1, "b" to 0.7, "c" to 0.2))
        ranking.best shouldBe "b"
        ranking.confidence shouldBe 0.7
        ranking.scores.map { it.first } shouldBe listOf("b", "c", "a")
    }

    test("margin says how far clear the winner ran, so a guess can be spotted") {
        Ranking.of("c", "f", listOf("a" to 0.9, "b" to 0.05)).margin shouldBeGreaterThan 0.8
        Ranking.of("c", "f", listOf("a" to 0.34, "b" to 0.33)).margin shouldBeGreaterThan 0.0
        Ranking.of("c", "f", listOf("a" to 0.34, "b" to 0.33)).margin shouldBe (0.01 plusOrMinus 1e-9)
    }

    test("an unsorted ranking is a bug, not something to quietly fix") {
        shouldThrow<IllegalArgumentException> { Ranking("c", "f", listOf("a" to 0.1, "b" to 0.9)) }
    }

    test("DeclaredOrder picks the first option and keeps the declared order in its scores") {
        val choice = Choice("c", "state", "which?", options(4))
        val ranking = DeclaredOrder.choose(choice)
        ranking.best shouldBe "o1"
        ranking.scores.map { it.first } shouldBe listOf("o1", "o2", "o3", "o4")
        ranking.scores.sumOf { it.second } shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("summary is one line a turn log can carry") {
        Ranking.of("c", "semif/mlx/Qwen", listOf("a" to 0.79, "b" to 0.09))
            .summary() shouldBe "a 0.79 (next b 0.09) via semif/mlx/Qwen"
    }
})
