package vnes.launcher;

import knes.skiko.SkikoMainKt;

import java.io.File;

/**
 * SkikoUILauncher - A standalone application that launches the vNES emulator with the Skiko UI.
 * 
 * This class provides a simple way to launch the Skiko UI implementation of the vNES emulator.
 */
public class SkikoLauncher {
    
    public static void main(String[] args) {
        // Set security manager with permissions
        System.setProperty("java.security.policy", "all.policy");
        
        // Check if a ROM file was provided as an argument
        if (args.length > 0 && new File(args[0]).exists()) {
            // TODO: Pass ROM file to Skiko UI when that feature is implemented
            System.out.println("ROM file provided: " + args[0]);
        }
        
        // Launch the Skiko UI
        System.out.println("Launching vNES with Skiko UI...");
        SkikoMainKt.main();
    }
}