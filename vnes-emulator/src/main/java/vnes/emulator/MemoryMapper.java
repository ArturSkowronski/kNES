package vnes.emulator;
/*
vNES
Copyright © 2006-2013 Open Emulation Project

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import vnes.emulator.memory.MemoryAccess;
import vnes.emulator.rom.ROMData;

public interface MemoryMapper extends MemoryAccess {
    void init(NES nes);
    void loadROM(ROMData romData);
    void write(int address, short value);
    short load(int address);
    short joy1Read();
    short joy2Read();
    void reset();
    void clockIrqCounter();
    void loadBatteryRam();
    void destroy();
    void stateLoad(ByteBuffer buf);
    void stateSave(ByteBuffer buf);
    void setMouseState(boolean pressed, int x, int y);
    void latchAccess(int address);
}
