package vnes.emulator.papu

interface PAPUChannel {
    fun writeReg(address: Int, value: Short)
    fun clock()
    fun reset()
    fun channelEnabled(): Boolean
    val lengthStatus: Int
}