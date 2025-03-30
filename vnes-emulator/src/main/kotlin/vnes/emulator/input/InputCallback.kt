package vnes.emulator.input

/**
 * Platform-agnostic interface for handling input events.
 * This interface defines callbacks for button press and release events
 * without dependencies on specific UI frameworks.
 */
interface InputCallback {
    /**
     * Called when a button is pressed.
     *
     * @param buttonCode The code of the button that was pressed
     */
    fun buttonDown(buttonCode: Int)

    /**
     * Called when a button is released.
     *
     * @param buttonCode The code of the button that was released
     */
    fun buttonUp(buttonCode: Int)
}