package knes.emulator

/** Why [NES.runUntilStop] stopped. */
sealed interface DebugStop {
    /** The program counter this stopped at. */
    val pc: Int

    /** Execution reached a breakpoint. */
    data class Breakpoint(override val pc: Int) : DebugStop {
        override fun toString() = "breakpoint at %04X".format(pc)
    }

    /**
     * A watched RAM address holds a different value than it did after the previous
     * instruction. [pc] is where execution had got to when the change was noticed, which
     * is the instruction *after* the one that caused it.
     */
    data class ValueChanged(
        val address: Int,
        val from: Int,
        val to: Int,
        override val pc: Int,
    ) : DebugStop {
        override fun toString() = "%04X: %02X -> %02X (now at %04X)".format(address, from, to, pc)
    }
}
