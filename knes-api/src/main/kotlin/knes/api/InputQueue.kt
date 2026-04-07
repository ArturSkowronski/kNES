package knes.api

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

data class FrameInput(val buttons: Set<Int>)

/**
 * Frame-synchronized input queue for delivering button state to the NES one frame at a time.
 *
 * Thread safety: [enqueue] and [advanceFrame] are synchronized internally via [lock].
 * They may be called from different threads (API thread and UI thread respectively).
 */
class InputQueue {
    private val lock = Any()
    private val queue = ConcurrentLinkedQueue<FrameInput>()
    private val latches = ConcurrentLinkedQueue<LatchEntry>()

    @Volatile
    var currentFrame: FrameInput? = null
        private set

    val isActive: Boolean get() = currentFrame != null

    fun enqueue(inputs: List<FrameInput>): CountDownLatch {
        require(inputs.isNotEmpty()) { "inputs must not be empty" }
        val latch = CountDownLatch(inputs.size)

        synchronized(lock) {
            latches.add(LatchEntry(latch, AtomicInteger(inputs.size)))
            queue.addAll(inputs)

            if (currentFrame == null) {
                currentFrame = queue.poll()
            }
        }

        return latch
    }

    fun advanceFrame() {
        synchronized(lock) {
            if (currentFrame == null) return
            countDownOldest()
            currentFrame = queue.poll()
        }
    }

    fun isPressed(padKey: Int): Boolean = currentFrame?.buttons?.contains(padKey) == true

    private fun countDownOldest() {
        val entry = latches.peek() ?: return
        entry.latch.countDown()
        if (entry.remaining.decrementAndGet() <= 0) {
            latches.poll()
        }
    }

    private class LatchEntry(val latch: CountDownLatch, val remaining: AtomicInteger)
}
