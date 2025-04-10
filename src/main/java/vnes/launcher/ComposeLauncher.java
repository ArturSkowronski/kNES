package vnes.launcher;

import knes.compose.ComposeMainKt;

import java.io.File;

/**
 * ComposeUILauncher - A standalone application that launches the vNES emulator with the Compose UI.
 * 
 * This class provides a simple way to launch the Compose UI implementation of the vNES emulator.
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
        System.out.println("Launching vNES with Compose UI...");
        ComposeMainKt.main();
    }
}