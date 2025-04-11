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

import knes.emulator.input.InputHandler;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class AppletInputHandler implements KeyListener, InputHandler {

    boolean[] allKeysState;
    int[] keyMapping;
    int id;
    Runnable menuInterface;

    public AppletInputHandler(int id) {
        this.id = id;
        allKeysState = new boolean[255];
        keyMapping = new int[InputHandler.NUM_KEYS];
    }

    public short getKeyState(int padKey) {
        return (short) (allKeysState[keyMapping[padKey]] ? 0x41 : 0x40);
    }

    public void mapKey(int padKey, int kbKeycode) {
        keyMapping[padKey] = kbKeycode;
    }

    public void keyPressed(KeyEvent ke) {

        int kc = ke.getKeyCode();
        if (kc >= allKeysState.length) {
            return;
        }

        allKeysState[kc] = true;

        // Can't hold both left & right or up & down at same time:
        if (kc == keyMapping[InputHandler.KEY_LEFT]) {
            allKeysState[keyMapping[InputHandler.KEY_RIGHT]] = false;
        } else if (kc == keyMapping[InputHandler.KEY_RIGHT]) {
            allKeysState[keyMapping[InputHandler.KEY_LEFT]] = false;
        } else if (kc == keyMapping[InputHandler.KEY_UP]) {
            allKeysState[keyMapping[InputHandler.KEY_DOWN]] = false;
        } else if (kc == keyMapping[InputHandler.KEY_DOWN]) {
            allKeysState[keyMapping[InputHandler.KEY_UP]] = false;
        }
    }

    public void keyReleased(KeyEvent ke) {

        int kc = ke.getKeyCode();
        if (kc >= allKeysState.length) {
            return;
        }

        allKeysState[kc] = false;

        if (id == 0) {
            switch (kc) {
                case KeyEvent.VK_F5: {
                    menuInterface.run();
                    break;
                }
            }
        }
    }

    public void keyTyped(KeyEvent ke) {
        // Ignore.
    }

    public void reset() {
        allKeysState = new boolean[255];
    }

    public void update() {
        // doesn't do anything.
    }

    public void destroy() {
    }

}