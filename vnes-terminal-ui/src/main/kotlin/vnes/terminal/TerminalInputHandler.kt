package vnes.terminal

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

import java.awt.event.KeyEvent
import vnes.emulator.DestroyableInputHandler
import vnes.emulator.input.InputHandler.KEY_A
import vnes.emulator.input.InputHandler.KEY_B
import vnes.emulator.input.InputHandler.KEY_DOWN
import vnes.emulator.input.InputHandler.KEY_LEFT
import vnes.emulator.input.InputHandler.KEY_RIGHT
import vnes.emulator.input.InputHandler.KEY_SELECT
import vnes.emulator.input.InputHandler.KEY_START
import vnes.emulator.input.InputHandler.KEY_UP
import vnes.emulator.input.InputHandler.NUM_KEYS
import vnes.emulator.NES
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Input handler for the Terminal UI.
 * 
 * This implementation uses a simple command-line interface for input.
 */
class TerminalInputHandler(private val nes: NES) : DestroyableInputHandler {
    private val keyStates = ShortArray(NUM_KEYS) { 0 }
    private val keyMapping = IntArray(NUM_KEYS) { 0 }
    private val executor = Executors.newSingleThreadExecutor()
    private var running = true

    init {
        // Default key mappings (these are just for reference, as we'll use commands instead)
        mapKey(KEY_A, KeyEvent.VK_Z)
        mapKey(KEY_B, KeyEvent.VK_X)
        mapKey(KEY_START, KeyEvent.VK_ENTER)
        mapKey(KEY_SELECT, KeyEvent.VK_SPACE)
        mapKey(KEY_UP, KeyEvent.VK_UP)
        mapKey(KEY_DOWN, KeyEvent.VK_DOWN)
        mapKey(KEY_LEFT, KeyEvent.VK_LEFT)
        mapKey(KEY_RIGHT, KeyEvent.VK_RIGHT)

        // Start a thread to read commands from the console
        startCommandReader()
    }

    /**
     * Starts a thread to read commands from the console.
     */
    private fun startCommandReader() {
        executor.submit {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            println("Terminal UI Input Handler started. Type commands to control the emulator:")
            println("  a, b, start, select, up, down, left, right: Press the corresponding button")
            println("  release: Release all buttons")
            println("  quit: Exit the emulator")

            while (running) {
                try {
                    // Check if there's input available
                    if (System.`in`.available() > 0) {
                        val line = reader.readLine()
                        processCommand(line)
                    }
                    
                    // Sleep a bit to avoid busy waiting
                    Thread.sleep(100)
                } catch (e: Exception) {
                    println("Error reading input: ${e.message}")
                }
            }
        }
    }

    /**
     * Processes a command from the console.
     * 
     * @param command The command to process
     */
    private fun processCommand(command: String) {
        when (command.trim().lowercase()) {
            "a" -> setKeyState(KEY_A, true)
            "b" -> setKeyState(KEY_B, true)
            "start" -> setKeyState(KEY_START, true)
            "select" -> setKeyState(KEY_SELECT, true)
            "up" -> setKeyState(KEY_UP, true)
            "down" -> setKeyState(KEY_DOWN, true)
            "left" -> setKeyState(KEY_LEFT, true)
            "right" -> setKeyState(KEY_RIGHT, true)
            "release" -> {
                // Release all buttons
                for (i in 0 until NUM_KEYS) {
                    keyStates[i] = 0x40
                }
                println("All buttons released")
            }
            "quit" -> {
                println("Exiting emulator...")
                nes.stopEmulation()
                running = false
                System.exit(0)
            }
            else -> println("Unknown command: $command")
        }
    }

    /**
     * Sets the state of a key.
     * 
     * @param padKey The pad key
     * @param isPressed Whether the key is pressed
     */
    private fun setKeyState(padKey: Int, isPressed: Boolean) {
        keyStates[padKey] = if (isPressed) 0x41 else 0x40
        println("Button ${getKeyName(padKey)} ${if (isPressed) "pressed" else "released"}")
    }

    /**
     * Gets the name of a key.
     * 
     * @param padKey The pad key
     * @return The name of the key
     */
    private fun getKeyName(padKey: Int): String {
        return when (padKey) {
            KEY_A -> "A"
            KEY_B -> "B"
            KEY_START -> "Start"
            KEY_SELECT -> "Select"
            KEY_UP -> "Up"
            KEY_DOWN -> "Down"
            KEY_LEFT -> "Left"
            KEY_RIGHT -> "Right"
            else -> "Unknown"
        }
    }

    /**
     * Gets the state of a key.
     * 
     * @param padKey The key to check
     * @return 0x41 if the key is pressed, 0x40 otherwise
     */
    override fun getKeyState(padKey: Int): Short {
        return keyStates[padKey]
    }

    /**
     * Maps a pad key to a device key.
     * 
     * @param padKey The pad key to map
     * @param deviceKey The device key to map to
     */
    override fun mapKey(padKey: Int, deviceKey: Int) {
        keyMapping[padKey] = deviceKey
    }

    /**
     * Resets the input handler.
     */
    override fun reset() {
        for (i in keyStates.indices) {
            keyStates[i] = 0
        }
    }

    /**
     * Updates the input handler.
     */
    override fun update() {
        // No need to update key states here, as they are updated by the command reader
    }

    /**
     * Cleans up resources.
     */
    override fun destroy() {
        running = false
        executor.shutdown()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            println("Error shutting down input handler: ${e.message}")
        }
    }
}