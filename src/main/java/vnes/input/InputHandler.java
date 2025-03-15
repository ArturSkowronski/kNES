package vnes.input;
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

public interface InputHandler {

    // Joypad keys:
    int KEY_A = 0;
    int KEY_B = 1;
    int KEY_START = 2;
    int KEY_SELECT = 3;
    int KEY_UP = 4;
    int KEY_DOWN = 5;
    int KEY_LEFT = 6;
    int KEY_RIGHT = 7;
    
    // Key count:
    int NUM_KEYS = 8;

    short getKeyState(int padKey);

    void mapKey(int padKey, int deviceKey);

    void reset();

    void update();
}