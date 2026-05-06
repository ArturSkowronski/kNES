package knes.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Tests the regex used by AgentSession to extract UNEXPECTED interior entries
 *  from toolCallLog entries written by WalkOverworldTo.aborted (targeted=false).
 *  Critical that the mapId capture works correctly — mapId=0 indicates an
 *  UnknownMapTrap, which should NOT be recorded as a warp. */
class AgentSessionFailedWarpRegexTest : FunSpec({
    test("captures world coords AND mapId from a real WalkOverworldTo abort message") {
        val msg = "walkOverworldTo.aborted=entered interior after 6 steps; " +
            "world=(147,155) mapId=0 party=(16,23) targeted=false"
        val m = AgentSession.FAILED_WARP_REGEX.find(msg)!!
        m.groupValues[1] shouldBe "147"
        m.groupValues[2] shouldBe "155"
        m.groupValues[3] shouldBe "0"
    }

    test("captures non-zero mapId — real warp transitions like Coneria mapId=8") {
        val msg = "walkOverworldTo.aborted=entered interior after 4 steps; " +
            "world=(145,152) mapId=8 party=(11,32) targeted=false"
        val m = AgentSession.FAILED_WARP_REGEX.find(msg)!!
        m.groupValues[1] shouldBe "145"
        m.groupValues[2] shouldBe "152"
        m.groupValues[3] shouldBe "8"
    }

    test("does not match targeted=true (successful entry, not auto-detect candidate)") {
        val msg = "walkOverworldTo.aborted=entered interior after 4 steps; " +
            "world=(145,152) mapId=8 party=(11,32) targeted=true"
        AgentSession.FAILED_WARP_REGEX.find(msg) shouldBe null
    }

    test("captures mapId across the world→targeted span containing party=(...)") {
        // Make sure the [^|]* between mapId and targeted doesn't break on the
        // party= field's parentheses.
        val msg = "world=(99,88) mapId=42 party=(7,7) extra=garbage targeted=false"
        val m = AgentSession.FAILED_WARP_REGEX.find(msg)!!
        m.groupValues[3] shouldBe "42"
    }

    test("handles negative mapId (-1 sentinel from RamObserver fallback)") {
        val msg = "world=(10,20) mapId=-1 party=(0,0) targeted=false"
        val m = AgentSession.FAILED_WARP_REGEX.find(msg)!!
        m.groupValues[3] shouldBe "-1"
    }
})
