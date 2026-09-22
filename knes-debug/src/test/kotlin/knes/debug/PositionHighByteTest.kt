package knes.debug

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * A coordinate the console keeps in two bytes is only meaningful as the pair.
 *
 * Super Mario Bros holds Mario's x on screen and the page the screen has scrolled to.
 * Watching `playerX` alone, walking right across a scroll looks like standing still — and
 * anything that gives up when nothing moves gives up on a goal that is working.
 */
class PositionHighByteTest : FunSpec({

    val mario = PositionMapping(
        localXFields = listOf("playerX"),
        localXHighFields = listOf("screenPage"),
        localYFields = listOf("playerY"),
    )

    test("the high byte is worth 256 of the low one") {
        mario.localX(mapOf("playerX" to 40, "screenPage" to 2)) shouldBe 552
        mario.localY(mapOf("playerY" to 176)) shouldBe 176
    }

    test("walking right across a scroll is movement, not standing still") {
        val before = mario.localX(mapOf("playerX" to 250, "screenPage" to 0))!!
        val after = mario.localX(mapOf("playerX" to 6, "screenPage" to 1))!!
        (after > before) shouldBe true
    }

    test("no high byte declared, and the low one is the whole answer") {
        val ff1 = PositionMapping(localXFields = listOf("smPlayerX"))
        ff1.localX(mapOf("smPlayerX" to 18)) shouldBe 18
    }

    test("a high byte missing from the snapshot leaves the low one alone") {
        mario.localX(mapOf("playerX" to 40)) shouldBe 40
    }

    test("no low byte, no position — a high byte on its own says nothing") {
        mario.localX(mapOf("screenPage" to 2)).shouldBeNull()
    }
})
