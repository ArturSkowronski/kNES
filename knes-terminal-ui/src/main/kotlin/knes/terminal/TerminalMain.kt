package knes.terminal

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

import vnes.emulator.NES
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
    private val nes = NES(uiFactory, screenView)
    private val terminalUI = uiFactory.getTerminalUI()

    init {
        // Set PPU logging flag
        nes.ppu!!.isEnablePpuLogging = enablePpuLogging
    }

    /**
     * Starts the application.
     * 
     * @param args Command line arguments
     */
    fun start(args: Array<String>) {
        println("vNES Terminal UI")
        println("================")

        // Initialize the UI
        terminalUI.init(nes, screenView)

        // Check if a ROM file was specified as a command line argument
        var romPath: String? = null
        if (args.isNotEmpty()) {
            romPath = args[0]
        }

        // If no ROM file was specified, look for vnes.nes in the current directory
        if (romPath == null) {
            val defaultRom = File("vnes.nes")
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
