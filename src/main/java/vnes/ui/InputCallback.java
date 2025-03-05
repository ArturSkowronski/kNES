package vnes.ui;
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

/**
 * Platform-agnostic interface for handling input events.
 * This interface defines callbacks for button press and release events
 * without dependencies on specific UI frameworks.
 */
public interface InputCallback {
    /**
     * Called when a button is pressed.
     * 
     * @param buttonCode The code of the button that was pressed
     */
    void buttonDown(int buttonCode);
    
    /**
     * Called when a button is released.
     * 
     * @param buttonCode The code of the button that was released
     */
    void buttonUp(int buttonCode);
}
