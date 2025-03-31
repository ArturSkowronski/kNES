package vnes.emulator;

import vnes.emulator.channels.IChannel;

import java.util.HashMap;
import java.util.Map;

public class ChannelRegistry {
    private final Map<Integer, IChannel> addressToChannelMap = new HashMap<>();

    public void registerChannel(int startAddr, int endAddr, IChannel channel) {
        for (int addr = startAddr; addr <= endAddr; addr++) {
            addressToChannelMap.put(addr, channel);
        }
    }

    public IChannel getChannel(int address) {
        return addressToChannelMap.get(address);
    }
}
