package vnes.emulator.producers

import vnes.emulator.papu.ChannelRegistry
import vnes.emulator.papu.IAudioContext
import vnes.emulator.papu.channels.ChannelDM
import vnes.emulator.papu.channels.ChannelNoise
import vnes.emulator.papu.channels.ChannelSquare
import vnes.emulator.papu.channels.ChannelTriangle

class ChannelRegistryProducer {
    fun produce(audioContext: IAudioContext?): ChannelRegistry {
        val registry = ChannelRegistry()
        val square1 = ChannelSquare(audioContext, true)
        val square2 = ChannelSquare(audioContext, false)
        val triangle = ChannelTriangle(audioContext)
        val noise = ChannelNoise(audioContext)
        val dmc = ChannelDM(audioContext)

        // Register channels with registry
        registry.registerChannel(0x4000, 0x4003, square1)
        registry.registerChannel(0x4004, 0x4007, square2)
        registry.registerChannel(0x4008, 0x400B, triangle)
        registry.registerChannel(0x400C, 0x400F, noise)
        registry.registerChannel(0x4010, 0x4013, dmc)

        return registry
    }
}
