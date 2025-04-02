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

import vnes.emulator.input.InputCallback;
import vnes.emulator.input.InputHandler;
import vnes.emulator.ui.GUI;
import vnes.emulator.ui.PAPU_Applet_Functionality;
import vnes.emulator.utils.Globals;
import vnes.emulator.utils.HiResTimer;

/**
 * AWT-specific implementation of the UI interface.
 * This class implements the GUI interface directly, providing all necessary
 * functionality for the NES emulator UI.
 */
public class AppletGUI implements GUI {

    protected InputCallback[] inputCallbacks;
    protected InputHandler[] inputHandlers;

    private PAPU_Applet_Functionality papuProvider;
    private AppletMain applet;
    private AppletInputHandler kbJoy1;
    private AppletInputHandler kbJoy2;
    private AppletScreenView vScreen;
    private HiResTimer timer;
    private long t1, t2;
    private int sleepTime;

    /**
     * Create a new AppletUI for the specified applet.
     *
     * @param applet The vNES applet
     */
    public AppletGUI(AppletMain applet) {
        this.inputCallbacks = new InputCallback[2];
        this.inputHandlers = new InputHandler[2];

        timer = new HiResTimer();
        this.applet = applet;
    }

    @Override
    public void init(PAPU_Applet_Functionality papu_applet_functionality, boolean showGui) {
        // Create the screen view
        papuProvider = papu_applet_functionality;
        vScreen = new AppletScreenView( this,256, 240);
        vScreen.setBgColor(applet.bgColor.getRGB());
        vScreen.init();
        vScreen.setNotifyImageReady(true);

        // Create the input handlers
        kbJoy1 = new AppletInputHandler(0);
        kbJoy2 = new AppletInputHandler(1);

        // Set the input handlers
        inputHandlers[0] = kbJoy1;
        inputHandlers[1] = kbJoy2;

        // Grab Controller Setting for Player 1:
        kbJoy1.mapKey(InputHandler.KEY_A, Globals.keycodes.get(Globals.controls.get("p1_a")));
        kbJoy1.mapKey(InputHandler.KEY_B, Globals.keycodes.get(Globals.controls.get("p1_b")));
        kbJoy1.mapKey(InputHandler.KEY_START, Globals.keycodes.get(Globals.controls.get("p1_start")));
        kbJoy1.mapKey(InputHandler.KEY_SELECT, Globals.keycodes.get(Globals.controls.get("p1_select")));
        kbJoy1.mapKey(InputHandler.KEY_UP, Globals.keycodes.get(Globals.controls.get("p1_up")));
        kbJoy1.mapKey(InputHandler.KEY_DOWN, Globals.keycodes.get(Globals.controls.get("p1_down")));
        kbJoy1.mapKey(InputHandler.KEY_LEFT, Globals.keycodes.get(Globals.controls.get("p1_left")));
        kbJoy1.mapKey(InputHandler.KEY_RIGHT, Globals.keycodes.get(Globals.controls.get("p1_right")));
        vScreen.addKeyListener(kbJoy1);

        // Grab Controller Setting for Player 2:
        kbJoy2.mapKey(InputHandler.KEY_A, Globals.keycodes.get(Globals.controls.get("p2_a")));
        kbJoy2.mapKey(InputHandler.KEY_B, Globals.keycodes.get(Globals.controls.get("p2_b")));
        kbJoy2.mapKey(InputHandler.KEY_START, Globals.keycodes.get(Globals.controls.get("p2_start")));
        kbJoy2.mapKey(InputHandler.KEY_SELECT, Globals.keycodes.get(Globals.controls.get("p2_select")));
        kbJoy2.mapKey(InputHandler.KEY_UP, Globals.keycodes.get(Globals.controls.get("p2_up")));
        kbJoy2.mapKey(InputHandler.KEY_DOWN, Globals.keycodes.get(Globals.controls.get("p2_down")));
        kbJoy2.mapKey(InputHandler.KEY_LEFT, Globals.keycodes.get(Globals.controls.get("p2_left")));
        kbJoy2.mapKey(InputHandler.KEY_RIGHT, Globals.keycodes.get(Globals.controls.get("p2_right")));
        vScreen.addKeyListener(kbJoy2);
    }


    @Override
    public void imageReady(boolean skipFrame) {
        // Sound stuff:
        int tmp = papuProvider.getBufferIndex();
        if (Globals.enableSound && Globals.timeEmulation && tmp > 0) {
            int min_avail = papuProvider.getLine().getBufferSize() - 4 * tmp;

            long timeToSleep = papuProvider.getMillisToAvailableAbove(min_avail);
            do {
                try {
                    Thread.sleep(timeToSleep);
                } catch (InterruptedException e) {
                }
            } while ((timeToSleep = papuProvider.getMillisToAvailableAbove(min_avail)) > 0);

            papuProvider.writeBuffer();
        }

        // Sleep a bit if sound is disabled:
        if (Globals.timeEmulation && !Globals.enableSound) {
            sleepTime = Globals.frameTime;
            if ((t2 = timer.currentMicros()) - t1 < sleepTime) {
                timer.sleepMicros(sleepTime - (t2 - t1));
            }
        }

        // Update timer:
        t1 = t2;
    }

    public void showLoadProgress(int percentComplete) {

        // Show ROM load progress:
        applet.showLoadProgress(percentComplete);

        // Sleep a bit:
        timer.sleepMicros(20 * 1000);

    }

    @Override
    public void destroy() {
        for (int i = 0; i < inputHandlers.length; i++) {
            if (inputHandlers[i] != null) {
                inputHandlers[i].reset();
                inputHandlers[i].destroy();
                inputHandlers[i] = null;
            }
            inputCallbacks[i] = null;
        }

        // Clean up additional resources
        applet = null;
        vScreen = null;
        timer = null;
    }

    @Override
    public InputHandler getJoy1() {
        return kbJoy1;
    }

    @Override
    public InputHandler getJoy2() {
        return kbJoy2;
    }

    @Override
    public AppletScreenView getScreenView() {
        return vScreen;
    }

    @Override
    public HiResTimer getTimer() {
        return timer;
    }

    @Override
    public void println(String s) {
        // Not implemented for applet
    }

    @Override
    public void showErrorMsg(String msg) {
        System.out.println(msg);
    }
}
