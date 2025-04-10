package knes.emulator.papu

class ChannelRegistry {
    private val addressToChannelMap: MutableMap<Int?, PAPUChannel?> = HashMap<Int?, PAPUChannel?>()

    fun registerChannel(startAddr: Int, endAddr: Int, channel: PAPUChannel?) {
        for (addr in startAddr..endAddr) {
            addressToChannelMap.put(addr, channel)
        }
    }

    fun getChannel(address: Int): PAPUChannel? {
        return addressToChannelMap.get(address)
    }
}