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

import vnes.emulator.ui.GUI;
import vnes.emulator.ui.ScreenView;
import vnes.emulator.utils.HiResTimer;

/**
 * Adapter class that implements the GUI interface by delegating to components
 * created by a NESUIFactory.
 */
public class GUIAdapter implements GUI {
    private final DestroyableInputHandler inputHandler;
    private final ScreenView screenView;
    private final HiResTimer timer;
    private NES nes;
    
    /**
     * Creates a new GUIAdapter with the specified components.
     * 
     * @param inputHandler The input handler to use
     * @param screenView The screen view to use
     */
    public GUIAdapter(DestroyableInputHandler inputHandler, ScreenView screenView) {
        this.inputHandler = inputHandler;
        this.screenView = screenView;
        this.timer = new HiResTimer();
    }
    
    @Override
    public InputHandler getJoy1() {
        return inputHandler;
    }
    
    @Override
    public InputHandler getJoy2() {
        // Currently only supporting one input handler
        return null;
    }
    
    @Override
    public ScreenView getScreenView() {
        return screenView;
    }
    
    @Override
    public HiResTimer getTimer() {
        return timer;
    }
    
    @Override
    public void imageReady(boolean skipFrame) {
        screenView.imageReady(skipFrame);
    }
    
    @Override
    public void init(NES nes, boolean showGui) {
        this.nes = nes;
        screenView.init();
    }
    
    @Override
    public void println(String s) {
        System.out.println(s);
    }
    
    @Override
    public void showErrorMsg(String msg) {
        System.err.println("ERROR: " + msg);
    }
    
    @Override
    public void showLoadProgress(int percentComplete) {
        // Default implementation does nothing
    }
    
    /**
     * Cleans up resources used by this GUI adapter.
     */
    public void destroy() {
        if (inputHandler != null) {
            inputHandler.destroy();
        }
        
        if (screenView != null) {
            screenView.destroy();
        }
    }
}
