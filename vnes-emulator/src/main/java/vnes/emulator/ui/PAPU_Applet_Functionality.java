package vnes.emulator.ui;
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

import javax.sound.sampled.SourceDataLine;

/**
 * Interface for providing access to the PAPU (Programmable Audio Processing Unit) of the NES.
 * This interface abstracts the PAPU-related functionality from the NES class.
 */
public interface PAPU_Applet_Functionality {
    
    /**
     * Gets the PAPU instance.
     * 
     * @return The PAPU instance
     */
    int getBufferIndex();
    SourceDataLine getLine();
    int getMillisToAvailableAbove(int target_avail);
    void writeBuffer();
}