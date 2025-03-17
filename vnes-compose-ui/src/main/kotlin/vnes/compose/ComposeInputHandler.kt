package vnes.compose

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

import vnes.emulator.DestroyableInputHandler
import vnes.emulator.NES

/**
 * Input handler for the Compose UI.
 */
class ComposeInputHandler(private val nes: NES) : DestroyableInputHandler {
    private val keyStates = ShortArray(NUM_KEYS) { 0 }
    private val keyMapping = IntArray(NUM_KEYS) { 0 }
    
    init {
        // Default key mappings
        mapKey(KEY_A, 'Z'.code)
        mapKey(KEY_B, 'X'.code)
        mapKey(KEY_START, 10) // Enter
        mapKey(KEY_SELECT, 32) // Space
        mapKey(KEY_UP, 38) // Up arrow
        mapKey(KEY_DOWN, 40) // Down arrow
        mapKey(KEY_LEFT, 37) // Left arrow
        mapKey(KEY_RIGHT, 39) // Right arrow
    }
    
    /**
     * Gets the state of a key.
     * 
     * @param padKey The key to check
     * @return 0x41 if the key is pressed, 0x40 otherwise
     */
    override fun getKeyState(padKey: Int): Short {
        return keyStates[padKey]
    }
    
    /**
     * Maps a pad key to a device key.
     * 
     * @param padKey The pad key to map
     * @param deviceKey The device key to map to
     */
    override fun mapKey(padKey: Int, deviceKey: Int) {
        keyMapping[padKey] = deviceKey
    }
    
    /**
     * Resets the input handler.
     */
    override fun reset() {
        for (i in keyStates.indices) {
            keyStates[i] = 0
        }
    }
    
    /**
     * Updates the input handler.
     */
    override fun update() {
        // Update key states based on Compose input
    }
    
    /**
     * Sets the state of a key.
     * 
     * @param keyCode The key code
     * @param isPressed Whether the key is pressed
     */
    fun setKeyState(keyCode: Int, isPressed: Boolean) {
        for (i in keyMapping.indices) {
            if (keyMapping[i] == keyCode) {
                keyStates[i] = if (isPressed) 0x41 else 0x40
            }
        }
    }
    
    /**
     * Cleans up resources.
     */
    override fun destroy() {
        // Clean up resources
    }
}
