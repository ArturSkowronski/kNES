package knes.api

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

data class FrameInput(val buttons: Set<Int>)

class InputQueue {
    private val queue = ConcurrentLinkedQueue<FrameInput>()
    private val latches = ConcurrentLinkedQueue<LatchEntry>()

    @Volatile
    var currentFrame: FrameInput? = null
        private set

    val isActive: Boolean get() = currentFrame != null

    fun enqueue(inputs: List<FrameInput>): CountDownLatch {
        require(inputs.isNotEmpty()) { "inputs must not be empty" }
        val latch = CountDownLatch(inputs.size)
        latches.add(LatchEntry(latch, inputs.size))

        val isFirstEntry = currentFrame == null
        queue.addAll(inputs)

        if (isFirstEntry) {
            currentFrame = queue.poll()
        }

        return latch
    }

    fun advanceFrame() {
        if (currentFrame == null) return
        countDownOldest()
        currentFrame = queue.poll()
    }

    fun isPressed(padKey: Int): Boolean = currentFrame?.buttons?.contains(padKey) == true

    private fun countDownOldest() {
        val entry = latches.peek() ?: return
        entry.latch.countDown()
        entry.remaining--
        if (entry.remaining <= 0) {
            latches.poll()
        }
    }

    private data class LatchEntry(val latch: CountDownLatch, var remaining: Int)
}
