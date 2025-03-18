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

import vnes.emulator.DestroyableInputHandler;
import vnes.emulator.NES;
import vnes.emulator.NESUIFactory;
import vnes.emulator.ui.ScreenView;
import vnes.applet.input.KbInputHandler;

/**
 * Factory for creating Applet UI components for the NES emulator.
 */
public class AppletUIFactory implements NESUIFactory {
    private final AppletUI appletUI;
    
    /**
     * Creates a new AppletUIFactory.
     */
    public AppletUIFactory() {
        this.appletUI = new AppletUI();
    }

    @Override
    public DestroyableInputHandler createInputHandler(NES nes) {
        return new KbInputHandler(nes);
    }

    @Override
    public ScreenView createScreenView(int scale) {
        BufferView bufferView = new BufferView();
        bufferView.setScale(scale);
        return bufferView;
    }
    
    @Override
    public void configureUISettings(boolean enableAudio, int fpsLimit) {
        // Configure applet-specific settings
    }
    
    /**
     * Gets the AppletUI instance.
     * 
     * @return The AppletUI instance
     */
    public AppletUI getAppletUI() {
        return appletUI;
    }
}
