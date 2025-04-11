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

package knes.launcher;

import knes.compose.ComposeMainKt;

import java.io.File;

/**
 * ComposeUILauncher - A standalone application that launches the kNES emulator with the Compose UI.
 * 
 * This class provides a simple way to launch the Compose UI implementation of the kNES emulator.
 */
public class ComposeLauncher {
    
    public static void main(String[] args) {
        // Set security manager with permissions
        System.setProperty("java.security.policy", "all.policy");
        
        // Check if a ROM file was provided as an argument
        if (args.length > 0 && new File(args[0]).exists()) {
            // TODO: Pass ROM file to Compose UI when that feature is implemented
            System.out.println("ROM file provided: " + args[0]);
        }
        
        // Launch the Compose UI
        System.out.println("Launching kNES with Compose UI...");
        ComposeMainKt.main();
    }
}