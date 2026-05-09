package knes.agent.runtime

/**
 * Bounded FIFO of the last 4 strategic decisions. Capacity 4 because the
 * anti-thrash predicate needs to detect strict GRIND/REST alternation across
 * 4 entries; advisor prompt only sees last 3.
 */
class RecentDecisionsBuffer(private val capacity: Int = 4) {
    private val deque: ArrayDeque<StrategicDecision> = ArrayDeque(capacity)

    fun record(d: StrategicDecision) {
        if (deque.size == capacity) deque.removeFirst()
        deque.addLast(d)
    }

    fun snapshot(): List<StrategicDecision> = deque.toList()

    fun lastN(n: Int): List<StrategicDecision> {
        if (n >= deque.size) return deque.toList()
        return deque.toList().subList(deque.size - n, deque.size)
    }

    /**
     * True when the buffer is full AND the entries strictly alternate between
     * GRIND and REST. BRIDGE in the window disables thrash detection.
     */
    fun isThrashing(): Boolean {
        if (deque.size < capacity) return false
        val list = deque.toList()
        if (list.any { it == StrategicDecision.BRIDGE }) return false
        return (0 until list.size - 1).all { i -> list[i] != list[i + 1] }
    }
}
