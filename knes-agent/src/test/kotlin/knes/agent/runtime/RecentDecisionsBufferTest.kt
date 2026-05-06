package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class RecentDecisionsBufferTest : FunSpec({
    test("starts empty, holds at most 4 entries") {
        val buf = RecentDecisionsBuffer()
        buf.snapshot() shouldBe emptyList()
        listOf(
            StrategicDecision.GRIND, StrategicDecision.GRIND, StrategicDecision.GRIND,
            StrategicDecision.REST, StrategicDecision.GRIND
        ).forEach(buf::record)
        buf.snapshot() shouldBe listOf(
            StrategicDecision.GRIND, StrategicDecision.GRIND, StrategicDecision.REST, StrategicDecision.GRIND
        )
    }
    test("lastN returns most recent N (clamped)") {
        val buf = RecentDecisionsBuffer()
        buf.record(StrategicDecision.GRIND)
        buf.record(StrategicDecision.REST)
        buf.record(StrategicDecision.GRIND)
        buf.lastN(2) shouldBe listOf(StrategicDecision.REST, StrategicDecision.GRIND)
        buf.lastN(10) shouldBe listOf(StrategicDecision.GRIND, StrategicDecision.REST, StrategicDecision.GRIND)
    }
    test("isThrashing detects strict 4-entry GRIND/REST alternation") {
        val buf = RecentDecisionsBuffer()
        buf.isThrashing().shouldBeFalse()
        buf.record(StrategicDecision.GRIND)
        buf.record(StrategicDecision.REST)
        buf.record(StrategicDecision.GRIND)
        buf.isThrashing().shouldBeFalse()
        buf.record(StrategicDecision.REST)
        buf.isThrashing().shouldBeTrue()
    }
    test("isThrashing also detects REST-GRIND-REST-GRIND") {
        val buf = RecentDecisionsBuffer()
        listOf(
            StrategicDecision.REST, StrategicDecision.GRIND,
            StrategicDecision.REST, StrategicDecision.GRIND
        ).forEach(buf::record)
        buf.isThrashing().shouldBeTrue()
    }
    test("isThrashing false when BRIDGE is in the window") {
        val buf = RecentDecisionsBuffer()
        listOf(
            StrategicDecision.GRIND, StrategicDecision.REST,
            StrategicDecision.BRIDGE, StrategicDecision.GRIND
        ).forEach(buf::record)
        buf.isThrashing().shouldBeFalse()
    }
})
