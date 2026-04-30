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

    test("getKeyState merges queue input with persistent holds") {
        val c = ApiController()
        c.pressButton(InputHandler.KEY_A) // persistent hold

        val latch = c.enqueueSteps(listOf(StepRequest(listOf("B"), 1)))
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()  // persistent
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()  // from queue

        c.onFrameBoundary() // consume queue entry
        latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe true
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()  // still persistent
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()  // queue empty
    }

    test("enqueueSteps converts StepRequest to FrameInput") {
        val c = ApiController()
        val latch = c.enqueueSteps(listOf(
            StepRequest(listOf("A"), 2),
            StepRequest(emptyList(), 1),
            StepRequest(listOf("B"), 1)
        ))
        // 2 + 1 + 1 = 4 frames total
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()

        c.onFrameBoundary() // frame 2 of A
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x41.toShort()

        c.onFrameBoundary() // empty frame
        c.getKeyState(InputHandler.KEY_A) shouldBe 0x40.toShort()
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()

        c.onFrameBoundary() // B frame
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()

        c.onFrameBoundary() // done
        latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe true
        c.getKeyState(InputHandler.KEY_B) shouldBe 0x40.toShort()
    }

    test("onFrameBoundary is safe when no queue active") {
        val c = ApiController()
        c.onFrameBoundary() // should not throw
    }
})
