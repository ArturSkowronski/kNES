package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import knes.emulator.input.InputHandler

class ApiControllerTest : FunSpec({

    test("initial state: all buttons released") {
        val c = ApiController()
        for (i in 0 until InputHandler.NUM_KEYS) {
            c.getKeyState(i) shouldBe 0x40.toShort()
        }
        c.getHeldButtons() shouldBe emptyList()
    }

    test("pressButton and releaseButton") {
        val c = ApiController()
        c.pressButton(InputHandler.KEY_A)
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()
        c.getHeldButtons() shouldContainExactlyInAnyOrder listOf("A")

        c.releaseButton(InputHandler.KEY_A)
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x40.toShort()
        c.getHeldButtons() shouldBe emptyList()
    }

    test("setButtons sets multiple and releases previous") {
        val c = ApiController()
        c.setButtons(listOf("RIGHT", "A"))
        c.getHeldButtons() shouldContainExactlyInAnyOrder listOf("RIGHT", "A")

        c.setButtons(listOf("LEFT"))
        c.getHeldButtons() shouldContainExactlyInAnyOrder listOf("LEFT")
        c.getKeyState(InputHandler.KEY_RIGHT) shouldBe 0x40.toShort()
    }

    test("releaseAll clears everything") {
        val c = ApiController()
        c.setButtons(listOf("A", "B", "UP", "RIGHT"))
        c.releaseAll()
        c.getHeldButtons() shouldBe emptyList()
    }

    test("resolveButton maps names correctly") {
        val c = ApiController()
        c.resolveButton("A") shouldBe InputHandler.KEY_A
        c.resolveButton("right") shouldBe InputHandler.KEY_RIGHT
        c.resolveButton("START") shouldBe InputHandler.KEY_START
    }

    test("resolveButton throws on unknown button") {
        val c = ApiController()
        shouldThrow<IllegalArgumentException> {
            c.resolveButton("TURBO")
        }
    }

    test("setButtons is case-insensitive") {
        val c = ApiController()
        c.setButtons(listOf("a", "Right", "START"))
        c.getHeldButtons() shouldContainExactlyInAnyOrder listOf("A", "RIGHT", "START")
    }
})
