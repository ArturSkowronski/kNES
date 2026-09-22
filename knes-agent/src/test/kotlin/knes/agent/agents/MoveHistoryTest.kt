package knes.agent.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Executor tells whether a move worked by parsing `sm=(x,y)` back out of the tool's
 * own Ok message. When that parse fails it silently reports the party never moved, and
 * the model loses the one signal that stops it oscillating around a blocked tile.
 */
class MoveHistoryTest : FunSpec({

    test("reads the coordinates a sequence reports") {
        partyPositionIn(
            "sequence: tapped 1 buttons; sm=(18,14) world=(147,155) location=[0, 1]"
        ) shouldBe (18 to 14)
    }

    test("a space after the comma is still the same position") {
        // Pair.toString() renders "(18, 14)". The producer no longer relies on it, but a
        // parser that breaks on a space is how this failed silently for a whole run.
        partyPositionIn("sequence: tapped 1 buttons; sm=(18, 14) world=(147, 155)") shouldBe (18 to 14)
    }

    test("reads the coordinates a town walk reports") {
        partyPositionIn(
            "townWalk: adjacent to (11,11) at sm=(11,12) — blocked from exact step (recoveries=0)"
        ) shouldBe (11 to 12)
    }

    test("an unknown position is null, not a guess") {
        partyPositionIn("sequence: tapped 1 buttons; sm=(?,?) world=(?,?)") shouldBe null
        partyPositionIn("boot: pressed START") shouldBe null
    }

    test("negative coordinates parse rather than truncating") {
        partyPositionIn("sm=(-1,-2)") shouldBe (-1 to -2)
    }
})

/**
 * A menu tap never moves the party, so movement alone cannot distinguish "the dialog
 * advanced" from "that button does nothing here" — which is how a model ends up pressing
 * B at a dialog it imagined for a dozen turns.
 */
class ScreenEffectTest : FunSpec({

    test("a changed picture is progress, even with the party standing still") {
        screenEffectIn("sequence: tapped 1 buttons; sm=(11,10) location=[0, 1] screen=changed") shouldBe
            ExecutorAgent.ScreenEffect.CHANGED
    }

    test("an unchanged picture with no movement is a genuine no-op") {
        screenEffectIn("sequence: tapped 1 buttons; sm=(11,10) location=[0, 1] screen=unchanged") shouldBe
            ExecutorAgent.ScreenEffect.UNCHANGED
    }

    test("a tool that cannot say reports nothing rather than guessing") {
        screenEffectIn("sequence: tapped 1 buttons; sm=(11,10) location=[0, 1]") shouldBe
            ExecutorAgent.ScreenEffect.UNKNOWN
        screenEffectIn("townWalk: reached (11,11) in 4 steps") shouldBe ExecutorAgent.ScreenEffect.UNKNOWN
    }
})
