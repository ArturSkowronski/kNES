package knes.emulator

/**
 * A fixed-size ring of recently executed instructions.
 *
 * Off by default and allocated only when switched on, so nothing is paid for it during
 * normal emulation. The point is answering "how did we get here" after the fact, which
 * is otherwise guesswork.
 *
 * [Entry.pc] is the real program counter — the address the opcode was fetched from —
 * not the CPU's internal `REG_PC_NEW`, which trails it by one.
 */
class InstructionTrace(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    /** One executed instruction, as the CPU saw it. */
    data class Entry(val pc: Int, val opcode: Int, val cycles: Int) {
        override fun toString(): String = "%04X: %02X (%d)".format(pc, opcode, cycles)
    }

    private val ring = arrayOfNulls<Entry>(capacity)
    private var next = 0

    /** Instructions recorded since the last [clear], capped at [capacity]. */
    var size: Int = 0
        private set

    /** Instructions executed since the last [clear], including ones already overwritten. */
    var executed: Long = 0L
        private set

    fun record(pc: Int, opcode: Int, cycles: Int) {
        ring[next] = Entry(pc, opcode, cycles)
        next = (next + 1) % capacity
        if (size < capacity) size++
        executed++
    }

    /** Oldest first. */
    fun entries(): List<Entry> {
        val start = if (size < capacity) 0 else next
        return (0 until size).mapNotNull { ring[(start + it) % capacity] }
    }

    /** The most recent [count] instructions, oldest first. */
    fun tail(count: Int): List<Entry> = entries().takeLast(count)

    fun clear() {
        ring.fill(null)
        next = 0
        size = 0
        executed = 0L
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1024
    }
}
