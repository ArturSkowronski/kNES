package knes.compose

import knes.emulator.input.InputHandler
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent

/**
 * Input handler for the Compose UI.
 *
 * Note: This is a temporary implementation using Swing instead of Compose
 * until the Compose UI dependencies are properly configured.
 */
class ComposeInputHandler() : InputHandler {
    private val keyStates = ShortArray(InputHandler.Companion.NUM_KEYS) { 0 }
    private val keyMapping = IntArray(InputHandler.Companion.NUM_KEYS) { 0 }
    private val keyAdapter = KeyInputAdapter()

    init {
        // Default key mappings
        mapKey(InputHandler.Companion.KEY_A, KeyEvent.VK_Z)
        mapKey(InputHandler.Companion.KEY_B, KeyEvent.VK_X)
        mapKey(InputHandler.Companion.KEY_START, KeyEvent.VK_ENTER)
        mapKey(InputHandler.Companion.KEY_SELECT, KeyEvent.VK_SPACE)
        mapKey(InputHandler.Companion.KEY_UP, KeyEvent.VK_UP)
        mapKey(InputHandler.Companion.KEY_DOWN, KeyEvent.VK_DOWN)
        mapKey(InputHandler.Companion.KEY_LEFT, KeyEvent.VK_LEFT)
        mapKey(InputHandler.Companion.KEY_RIGHT, KeyEvent.VK_RIGHT)
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
        // No need to update key states here, as they are updated by the key adapter
    }

    /**
     * Sets the state of a key.
     *
     * @param keyCode The key code
     * @param isPressed Whether the key is pressed
     */
    fun setKeyState(keyCode: Int, isPressed: Boolean) {
        for (i in keyMapping.indices) {
            if (keyMapping[i] == keyCode) {
                keyStates[i] = if (isPressed) 0x41 else 0x40
            }
        }
    }

    /**
     * Registers the key adapter with a component.
     *
     * @param component The component to register with
     */
    fun registerKeyAdapter(component: JComponent) {
        component.addKeyListener(keyAdapter)
        component.isFocusable = true
        component.requestFocus()
    }

    /**
     * Unregisters the key adapter from a component.
     *
     * @param component The component to unregister from
     */
    fun unregisterKeyAdapter(component: JComponent) {
        component.removeKeyListener(keyAdapter)
    }

    /**
     * Cleans up resources.
     */
    override fun destroy() {
        // Clean up resources
    }

    /**
     * Key adapter for handling keyboard input.
     */
    inner class KeyInputAdapter : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            setKeyState(e.keyCode, true)
        }

        override fun keyReleased(e: KeyEvent) {
            setKeyState(e.keyCode, false)
        }
    }
}