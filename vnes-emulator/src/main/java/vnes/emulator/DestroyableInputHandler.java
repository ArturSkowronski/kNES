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

import vnes.emulator.input.InputHandler;

/**
 * Interface for input handlers that need to be explicitly destroyed.
 * This interface extends InputHandler and adds a destroy method.
 */
public interface DestroyableInputHandler extends InputHandler {
    /**
     * Clean up resources used by this input handler.
     */
    void destroy();
}
