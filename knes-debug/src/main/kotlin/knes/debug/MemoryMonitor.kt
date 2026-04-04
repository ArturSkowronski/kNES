package knes.debug

import knes.emulator.Memory

/**
 * Reads NES RAM addresses defined by a [GameProfile] and returns named values.
 * Shared by the Compose UI monitor window and the REST API.
 */
class MemoryMonitor {
    private var watchMap: Map<String, Int> = emptyMap()
    var activeProfile: GameProfile? = null
        private set

    fun applyProfile(profile: GameProfile) {
        activeProfile = profile
        watchMap = profile.toWatchMap()
    }

    fun setAddresses(addresses: Map<String, Int>) {
        activeProfile = null
        watchMap = addresses
    }

    fun read(cpuMemory: Memory): Map<String, Int> {
        return watchMap.mapValues { (_, addr) ->
            cpuMemory.load(addr).toInt() and 0xFF
        }
    }

    fun getWatchedAddresses(): Map<String, Int> = watchMap
}
