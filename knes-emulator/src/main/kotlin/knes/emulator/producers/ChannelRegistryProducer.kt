package knes.emulator.producers

import knes.emulator.papu.ChannelRegistry
import knes.emulator.papu.PAPUAudioContext

import knes.emulator.papu.channels.ChannelDM
import knes.emulator.papu.channels.ChannelNoise
import knes.emulator.papu.channels.ChannelSquare
import knes.emulator.papu.channels.ChannelTriangle

class ChannelRegistryProducer {
    fun produce(audioContext: PAPUAudioContext?): ChannelRegistry {
        val registry = ChannelRegistry()
        val square1 = ChannelSquare(audioContext, true)
        val square2 = ChannelSquare(audioContext, false)
        val triangle = ChannelTriangle(audioContext)
        val noise = ChannelNoise(audioContext)
        val dmc = ChannelDM(audioContext)

        registry.registerChannel(0x4000, 0x4003, square1)
        registry.registerChannel(0x4004, 0x4007, square2)
        registry.registerChannel(0x4008, 0x400B, triangle)
        registry.registerChannel(0x400C, 0x400F, noise)
        registry.registerChannel(0x4010, 0x4013, dmc)

        return registry
    }
}
