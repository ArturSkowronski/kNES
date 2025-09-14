/*
 *
 *  * Copyright (C) 2025 Artur Skowroński
 *  * This file is part of kNES, a fork of vNES (GPLv3) rewritten in Kotlin.
 *  *
 *  * vNES was originally developed by Brian F. R. (bfirsh) and released under the GPL-3.0 license.
 *  * This project is a reimplementation and extension of that work.
 *  *
 *  * kNES is licensed under the GNU General Public License v3.0.
 *  * See the LICENSE file for more details.
 *
 */

package knes.applet;

import knes.emulator.input.InputCallback;
import knes.emulator.input.InputHandler;
import knes.emulator.ui.GUI;
import knes.emulator.ui.PAPU_Applet_Functionality;
import knes.emulator.utils.Globals;
import knes.emulator.utils.HiResTimer;
import org.jetbrains.annotations.NotNull;

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
     * @param applet The kNES applet
     */
    public AppletGUI(AppletMain applet) {
        this.inputCallbacks = new InputCallback[2];
        this.inputHandlers = new InputHandler[2];

        timer = new HiResTimer();
        this.applet = applet;
    }

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
    public void imageReady(boolean skipFrame, int [] buffer) {
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

    public void sendDebugMessage(int percentComplete) {

        // Show ROM load progress:
        applet.showLoadProgress(percentComplete);

        // Sleep a bit:
        timer.sleepMicros(20 * 1000);

    }

    @Override
    public void destroy() {
        for (int i = 0; i < inputHandlers.length; i++) {
            if (inputHandlers[i] != null) {
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

    public AppletScreenView getScreenView() {
        return vScreen;
    }

    @Override
    public HiResTimer getTimer() {
        return timer;
    }

    @Override
    public void sendErrorMsg(String msg) {
        System.out.println(msg);
    }

    @Override
    public void sendDebugMessage(@NotNull String message) {

    }
}
