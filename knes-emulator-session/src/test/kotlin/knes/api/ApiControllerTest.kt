package knes.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import java.util.concurrent.TimeUnit

class ApiControllerTest : FunSpec({

    test("all buttons are released initially") {
        ApiController().getHeldButtons() shouldBe emptyList()
    }

    test("setButtons holds exactly the named buttons") {
        val controller = ApiController()
        controller.setButtons(listOf("A", "RIGHT"))
        controller.getHeldButtons() shouldContainExactlyInAnyOrder listOf("A", "RIGHT")
    }

    test("setButtons releases whatever was held before") {
        val controller = ApiController()
        controller.setButtons(listOf("A", "B"))
        controller.setButtons(listOf("UP"))
        controller.getHeldButtons() shouldContainExactlyInAnyOrder listOf("UP")
    }

    test("pressButton and releaseButton move a single button") {
        val controller = ApiController()
        controller.pressButton(controller.resolveButton("START"))
        controller.getHeldButtons() shouldContainExactlyInAnyOrder listOf("START")
        controller.releaseButton(controller.resolveButton("START"))
        controller.getHeldButtons() shouldBe emptyList()
    }

    test("releaseAll clears every button") {
        val controller = ApiController()
        controller.setButtons(listOf("A", "B", "UP", "LEFT"))
        controller.releaseAll()
        controller.getHeldButtons() shouldBe emptyList()
    }

    test("button names are case insensitive") {
        val controller = ApiController()
        controller.setButtons(listOf("a", "Right", "START"))
        controller.getHeldButtons() shouldContainExactlyInAnyOrder listOf("A", "RIGHT", "START")
    }

    test("an unknown button name is rejected") {
        shouldThrow<IllegalArgumentException> { ApiController().resolveButton("TURBO") }
    }

    test("enqueueSteps expands step requests into per-frame input") {
        val controller = ApiController()
        val latch = controller.enqueueSteps(
            listOf(StepRequest(listOf("A"), 2), StepRequest(emptyList(), 1))
        )
        val queue = controller.inputQueue

        queue.isActive shouldBe true
        queue.isPressed(InputHandler.KEY_A) shouldBe true

        queue.advanceFrame()
        queue.isPressed(InputHandler.KEY_A) shouldBe true

        queue.advanceFrame()
        queue.isPressed(InputHandler.KEY_A) shouldBe false

        queue.advanceFrame()
        latch.await(100, TimeUnit.MILLISECONDS) shouldBe true
        queue.isActive shouldBe false
    }

    test("a queued press is visible through getKeyState even with nothing held") {
        val controller = ApiController()
        controller.enqueueSteps(listOf(StepRequest(listOf("B"), 1)))
        controller.getKeyState(InputHandler.KEY_B) shouldBe 0x41.toShort()
        controller.getKeyState(InputHandler.KEY_A) shouldBe 0x40.toShort()
    }
})
