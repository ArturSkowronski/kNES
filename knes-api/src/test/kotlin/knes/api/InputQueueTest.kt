package knes.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knes.emulator.input.InputHandler
import java.util.concurrent.TimeUnit

class InputQueueTest : FunSpec({

    test("initially inactive with nothing pressed") {
        val q = InputQueue()
        q.isActive shouldBe false
        q.isPressed(InputHandler.KEY_A) shouldBe false
    }

    test("enqueue sets currentFrame immediately") {
        val q = InputQueue()
        q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_A))))
        q.isActive shouldBe true
        q.isPressed(InputHandler.KEY_A) shouldBe true
        q.isPressed(InputHandler.KEY_B) shouldBe false
    }

    test("advanceFrame pops next entry") {
        val q = InputQueue()
        q.enqueue(listOf(
            FrameInput(setOf(InputHandler.KEY_A)),
            FrameInput(setOf(InputHandler.KEY_B))
        ))
        q.isPressed(InputHandler.KEY_A) shouldBe true

        q.advanceFrame()
        q.isPressed(InputHandler.KEY_A) shouldBe false
        q.isPressed(InputHandler.KEY_B) shouldBe true
    }

    test("advanceFrame clears currentFrame when queue empty") {
        val q = InputQueue()
        q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_A))))
        q.advanceFrame()
        q.isActive shouldBe false
        q.isPressed(InputHandler.KEY_A) shouldBe false
    }

    test("latch counts down on each advanceFrame") {
        val q = InputQueue()
        val latch = q.enqueue(listOf(
            FrameInput(setOf(InputHandler.KEY_A)),
            FrameInput(setOf(InputHandler.KEY_A)),
            FrameInput(setOf(InputHandler.KEY_A))
        ))
        latch.count shouldBe 3

        q.advanceFrame()
        latch.count shouldBe 2

        q.advanceFrame()
        latch.count shouldBe 1

        q.advanceFrame()
        latch.count shouldBe 0
        latch.await(0, TimeUnit.MILLISECONDS) shouldBe true
    }

    test("empty buttons enqueue correctly") {
        val q = InputQueue()
        val latch = q.enqueue(listOf(
            FrameInput(emptySet()),
            FrameInput(emptySet())
        ))
        q.isActive shouldBe true
        q.isPressed(InputHandler.KEY_A) shouldBe false

        q.advanceFrame()
        q.advanceFrame()
        latch.await(0, TimeUnit.MILLISECONDS) shouldBe true
    }

    test("advanceFrame with no queue is a no-op") {
        val q = InputQueue()
        q.advanceFrame() // should not throw
        q.isActive shouldBe false
    }

    test("second enqueue appends to existing queue") {
        val q = InputQueue()
        val latch1 = q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_A))))
        val latch2 = q.enqueue(listOf(FrameInput(setOf(InputHandler.KEY_B))))

        // First entry already set as currentFrame
        q.isPressed(InputHandler.KEY_A) shouldBe true

        q.advanceFrame() // completes first enqueue's entry, pops second
        latch1.await(0, TimeUnit.MILLISECONDS) shouldBe true
        q.isPressed(InputHandler.KEY_B) shouldBe true

        q.advanceFrame() // completes second enqueue's entry
        latch2.await(0, TimeUnit.MILLISECONDS) shouldBe true
        q.isActive shouldBe false
    }
})
