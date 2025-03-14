package vnes.applet;
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


import vnes.input.InputHandler;
import vnes.ui.UiInfoMessageBus;
import vnes.utils.HiResTimer;

/**
 * Legacy UI interface that extends the platform-agnostic NESUICore.
 * This interface maintains backward compatibility with AWT-specific implementations
 * while providing a bridge to the new platform-agnostic interface.
 */
public interface NotYetAbstractUI extends UiInfoMessageBus {

    // AWT-specific methods
    InputHandler getJoy1();
    InputHandler getJoy2();
    BufferView getScreenView();
    BufferView getPatternView();
    BufferView getSprPalView();
    BufferView getNameTableView();
    BufferView getImgPalView();
    HiResTimer getTimer();
    void imageReady(boolean skipFrame);
    void init(boolean showGui);
    int getRomFileSize();
    void println(String s);
}
