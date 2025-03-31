package vnes.emulator.channels;

public interface IChannel {
    void writeReg(int address, short value);
    void clock();
    void reset();
    void setEnabled(boolean enabled);
    boolean isEnabled();
    int getLengthStatus();
}
