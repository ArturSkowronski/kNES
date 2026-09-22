package knes.agent.decision

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private val CHOICE = Choice(
    id = "turn-7",
    state = "phase: Town",
    question = "Which of these should the party do on this turn?",
    options = listOf(Option("press_a", "Press A."), Option("step_north", "Walk one tile north.")),
)

class SemIfWireTest : FunSpec({

    test("a choice is already SemIf's row shape, so nothing is renamed on the way out") {
        val wire = SemIfProcess.wire(CHOICE).toString()
        wire shouldContain "\"id\":\"turn-7\""
        wire shouldContain "\"state\":\"phase: Town\""
        wire shouldContain "\"question\":"
        wire shouldContain "\"description\":\"Walk one tile north.\""
    }

    test("a reply is read back onto the options that were asked about") {
        val ranking = SemIfProcess.parseRanking(
            CHOICE,
            """{"id":"turn-7","option_ids":["press_a","step_north"],"probabilities":[0.2,0.8],"ms":88}""",
            "semif/mlx/Qwen",
        )
        ranking.best shouldBe "step_north"
        ranking.confidence shouldBe 0.8
        ranking.model shouldBe "semif/mlx/Qwen"
    }

    test("a reply about different options is a bug worth failing on, not a ranking to act on") {
        shouldThrow<IllegalArgumentException> {
            SemIfProcess.parseRanking(
                CHOICE,
                """{"id":"turn-7","option_ids":["press_a","step_south"],"probabilities":[0.5,0.5]}""",
                "m",
            )
        }.message shouldContain "declared"
    }

    test("a mismatched score count is rejected rather than zipped short") {
        shouldThrow<IllegalArgumentException> {
            SemIfProcess.parseRanking(
                CHOICE,
                """{"id":"turn-7","option_ids":["press_a","step_north"],"probabilities":[1.0]}""",
                "m",
            )
        }
    }

    test("the sidecar's own error is surfaced with the decision it belongs to") {
        shouldThrow<IllegalStateException> {
            SemIfProcess.parseRanking(CHOICE, """{"id":"turn-7","error":"ValueError: boom"}""", "m")
        }.message shouldContain "ValueError: boom"
    }
})

class PixelWireTest : FunSpec({

    test("a frame rides along on the wire when the model can look at it") {
        val withScreen = CHOICE.copy(imageB64 = "iVBORw0KGgo=")
        SemIfProcess.wire(withScreen).toString() shouldContain "\"image\":\"iVBORw0KGgo=\""
    }

    test("no frame, no field — the text backend would not know what to do with one") {
        SemIfProcess.wire(CHOICE).toString() shouldNotContain "image"
    }
})
