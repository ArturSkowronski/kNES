package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StrategicDecisionParserTest : FunSpec({
    test("parses bare GRIND/REST/BRIDGE tokens") {
        StrategicDecision.parse("GRIND") shouldBe StrategicDecision.GRIND
        StrategicDecision.parse("REST") shouldBe StrategicDecision.REST
        StrategicDecision.parse("BRIDGE") shouldBe StrategicDecision.BRIDGE
    }
    test("is case insensitive and trims whitespace") {
        StrategicDecision.parse("  grind  ") shouldBe StrategicDecision.GRIND
        StrategicDecision.parse("Rest\n") shouldBe StrategicDecision.REST
    }
    test("extracts token from a sentence") {
        StrategicDecision.parse("My decision is REST because HP low.") shouldBe StrategicDecision.REST
    }
    test("returns null on garbage / no token") {
        StrategicDecision.parse("LEVEL_UP") shouldBe null
        StrategicDecision.parse("") shouldBe null
        StrategicDecision.parse("   ") shouldBe null
    }
    test("first token wins when multiple appear") {
        StrategicDecision.parse("GRIND, then REST") shouldBe StrategicDecision.GRIND
    }
})
