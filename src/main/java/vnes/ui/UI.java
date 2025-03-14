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

import java.awt.*;

import vnes.utils.HiResTimer;
import vnes.input.InputCallback;
import vnes.input.InputHandler;

/**
 * Legacy UI interface that extends the platform-agnostic NESUICore.
 * This interface maintains backward compatibility with AWT-specific implementations
 * while providing a bridge to the new platform-agnostic interface.
 */
public interface UI extends NESUICore {

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
    String getWindowCaption();
    void setWindowCaption(String s);
    void setTitle(String s);
    Point getLocation();
    int getRomFileSize();
    void println(String s);
    
    // Default implementations of NESUICore methods that map to existing UI methods
    
    @Override
    default void initDisplay(int width, int height) {
        init(true);
    }
    
    @Override
    default DisplayBuffer getDisplayBuffer() {
        // Legacy implementations will need to adapt BufferView to DisplayBuffer
        return null;
    }
    
    @Override
    default void renderFrame(boolean skipFrame) {
        // The legacy implementation uses imageReady(skipFrame)
        imageReady(skipFrame);
    }
    
    @Override
    default void registerInputCallback(InputCallback callback, int playerIndex) {
        // Legacy implementations will need to adapt InputCallback to their input handling
    }
    
    @Override
    default void setInputHandler(InputHandler handler, int playerIndex) {
        // Legacy implementations handle this differently
        if (playerIndex == 0) {
            // This is a no-op in the interface, concrete classes should override
        } else if (playerIndex == 1) {
            // This is a no-op in the interface, concrete classes should override
        }
    }
}
