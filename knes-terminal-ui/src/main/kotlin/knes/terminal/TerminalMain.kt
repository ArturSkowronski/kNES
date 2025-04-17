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

package knes.terminal

import knes.controllers.KeyboardController
import knes.emulator.NES
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Main entry point for the Terminal UI.
 * 
 * Command line arguments:
 * - ROM file path: Path to the ROM file to load
 * - --disable-ppu-logging: Disable PPU logging
 */
fun main(args: Array<String>) {
    // Check for --disable-ppu-logging flag
    val enablePpuLogging = !args.contains("--disable-ppu-logging")

    // Remove the flag from args if present
    val filteredArgs = args.filter { it != "--disable-ppu-logging" }.toTypedArray()

    TerminalMain(enablePpuLogging).start(filteredArgs)
}

/**
 * Main class for the Terminal UI implementation.
 */
class TerminalMain(enablePpuLogging: Boolean = true) {
    private val uiFactory = TerminalUIFactory()
    private val screenView = uiFactory.createScreenView(1) as TerminalScreenView
    private val nes = NES(null, uiFactory, screenView, KeyboardController())
    private val terminalUI = uiFactory.getTerminalUI()

    init {
        // Set PPU logging flag
        nes.ppu.isEnablePpuLogging = enablePpuLogging
    }

    /**
     * Starts the application.
     * 
     * @param args Command line arguments
     */
    fun start(args: Array<String>) {
        println("kNES Terminal UI")
        println("================")

        // Initialize the UI
        terminalUI.init(nes, screenView)

        // Check if a ROM file was specified as a command line argument
        var romPath: String? = null
        if (args.isNotEmpty()) {
            romPath = args[0]
        }

        // If no ROM file was specified, look for knes.nes in the current directory
        if (romPath == null) {
            val defaultRom = File("knes.nes")
            if (defaultRom.exists()) {
                romPath = defaultRom.absolutePath
            }
        }

        // If still no ROM file, prompt the user to select one
        if (romPath == null) {
            romPath = promptForRomFile()
        }

        // If we have a ROM file, load it and start the emulator
        if (romPath != null) {
            if (terminalUI.loadRom(romPath)) {
                println("ROM loaded successfully: $romPath")
                println("Starting emulator...")
                println("Use the following commands to control the emulator:")
                println("  a, b, start, select, up, down, left, right: Press the corresponding button")
                println("  release: Release all buttons")
                println("  quit: Exit the emulator")
                println("\nNote: Use --disable-ppu-logging command line argument to disable PPU color logging")
                terminalUI.startEmulator()
            } else {
                println("Failed to load ROM: $romPath")
                System.exit(1)
            }
        } else {
            println("No ROM file specified. Exiting.")
            System.exit(1)
        }

        // Add a shutdown hook to clean up resources
        Runtime.getRuntime().addShutdownHook(Thread {
            terminalUI.destroy()
        })
    }

    /**
     * Prompts the user to select a ROM file.
     * 
     * @return The path to the selected ROM file, or null if no file was selected
     */
    private fun promptForRomFile(): String? {
        println("Please select a ROM file:")

        // Use Swing file chooser as a fallback
        val fileChooser = JFileChooser()
        fileChooser.fileFilter = FileNameExtensionFilter("NES ROMs", "nes")
        if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            return fileChooser.selectedFile.absolutePath
        }

        return null
    }
}
