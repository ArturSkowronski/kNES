package knes.api

import knes.controllers.ControllerProvider
import knes.emulator.input.InputHandler
import java.util.concurrent.CountDownLatch

class ApiController : ControllerProvider {
    private val keyStates = ShortArray(InputHandler.NUM_KEYS) { 0x40 }

    val inputQueue = InputQueue()

    private val buttonNames = mapOf(
        "A" to InputHandler.KEY_A,
        "B" to InputHandler.KEY_B,
        "START" to InputHandler.KEY_START,
        "SELECT" to InputHandler.KEY_SELECT,
        "UP" to InputHandler.KEY_UP,
        "DOWN" to InputHandler.KEY_DOWN,
        "LEFT" to InputHandler.KEY_LEFT,
        "RIGHT" to InputHandler.KEY_RIGHT,
    )

    fun pressButton(key: Int) { keyStates[key] = 0x41 }
    fun releaseButton(key: Int) { keyStates[key] = 0x40 }
    fun releaseAll() { keyStates.fill(0x40) }

    fun setButtons(buttons: List<String>) {
        releaseAll()
        for (name in buttons) {
            pressButton(resolveButton(name))
        }
    }

    fun getHeldButtons(): List<String> {
        return buttonNames.entries
            .filter { keyStates[it.value] == 0x41.toShort() }
            .map { it.key }
    }

    fun resolveButton(name: String): Int {
        return buttonNames[name.uppercase()]
            ?: throw IllegalArgumentException("Unknown button: $name. Valid: ${buttonNames.keys}")
    }

    fun enqueueSteps(steps: List<StepRequest>): CountDownLatch {
        val frameInputs = steps.flatMap { step ->
            val buttons = step.buttons.map { resolveButton(it) }.toSet()
            List(step.frames) { FrameInput(buttons) }
        }
        return inputQueue.enqueue(frameInputs)
    }

    fun onFrameBoundary() {
        inputQueue.advanceFrame()
    }

    override fun setKeyState(keyCode: Int, isPressed: Boolean) {}

    override fun getKeyState(padKey: Int): Short {
        val persistent = keyStates[padKey]
        val queued = if (inputQueue.isPressed(padKey)) 0x41.toShort() else 0x40.toShort()
        return if (persistent == 0x41.toShort() || queued == 0x41.toShort()) 0x41 else 0x40
    }
}
