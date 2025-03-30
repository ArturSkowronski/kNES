package vnes.emulator.input

interface InputHandler {
    fun getKeyState(padKey: Int): Short

    fun mapKey(padKey: Int, deviceKey: Int)

    fun reset()

    fun update()

    companion object {
        const val KEY_A: Int = 0
        const val KEY_B: Int = 1
        const val KEY_START: Int = 2
        const val KEY_SELECT: Int = 3
        const val KEY_UP: Int = 4
        const val KEY_DOWN: Int = 5
        const val KEY_LEFT: Int = 6
        const val KEY_RIGHT: Int = 7

        const val NUM_KEYS: Int = 8
    }
}