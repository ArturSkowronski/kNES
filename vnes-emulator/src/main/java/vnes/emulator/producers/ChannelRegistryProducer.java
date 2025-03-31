package vnes.emulator.producers;

import vnes.emulator.channels.*;
import vnes.emulator.ChannelRegistry;

public class ChannelRegistryProducer {

    public ChannelRegistry produce(IAudioContext audioContext) {
        ChannelRegistry registry = new ChannelRegistry();
        // Create channels with IAudioContext
        ChannelSquare square1 = new ChannelSquare(audioContext, true);
        ChannelSquare square2 = new ChannelSquare(audioContext, false);
        ChannelTriangle triangle = new ChannelTriangle(audioContext);
        ChannelNoise noise = new ChannelNoise(audioContext);
        ChannelDM dmc = new ChannelDM(audioContext);

        // Register channels with registry
        registry.registerChannel(0x4000, 0x4003, square1);
        registry.registerChannel(0x4004, 0x4007, square2);
        registry.registerChannel(0x4008, 0x400B, triangle);
        registry.registerChannel(0x400C, 0x400F, noise);
        registry.registerChannel(0x4010, 0x4013, dmc);

        return registry;
    }
}
