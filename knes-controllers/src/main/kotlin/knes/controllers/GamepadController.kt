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

package knes.controllers

import knes.emulator.input.InputHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Application
import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.ApplicationLogger
import com.badlogic.gdx.Audio
import com.badlogic.gdx.Files
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.Net
import com.badlogic.gdx.Preferences
import com.badlogic.gdx.utils.Clipboard
import com.badlogic.gdx.LifecycleListener
import com.badlogic.gdx.controllers.Controllers
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.utils.Array
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class GamepadController : ControllerProvider {

    private var controllers: Array<Controller>? = null
    var statusMessage: String = "Initializing..."
        private set

    init {
        try {
            if (Gdx.app == null) {
                Gdx.app = DummyApplication()
            }
            controllers = Controllers.getControllers()
            statusMessage = if (controllers == null || controllers!!.size == 0) {
                "No controllers found"
            } else {
                "Controllers: " + controllers!!.joinToString { it.name }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            statusMessage = "No controllers detected"
            controllers = null
        }
    }


    fun update() {
        // No update needed usually, it's event based or polling.
    }

    override fun getKeyState(padKey: Int): Short {
        try {
            val currentControllers = controllers
            if (currentControllers == null || currentControllers.size == 0) {
                return 0x40
            }
            val controller = currentControllers.first()

            val isPressed = if (controller.name.contains("Joy-Con (L)", ignoreCase = true)) {
                 // Joy-Con (L) Mapping (Sideways)
                 // Stick: Axis 0 (Horizontal), Axis 1 (Vertical)
                 // Buttons: 0=Left, 1=Down(B), 2=Up(X), 3=Right(A)
                 // Minus: 8 or 9
                 when (padKey) {
                    InputHandler.KEY_A -> controller.getButton(3) // Right Arrow -> A
                    InputHandler.KEY_B -> controller.getButton(1) // Down Arrow -> B
                    InputHandler.KEY_START -> controller.getButton(9) // Minus -> Start
                    InputHandler.KEY_SELECT -> controller.getButton(8) // Capture -> Select
                    
                    InputHandler.KEY_UP -> controller.getAxis(1) < -0.5f
                    InputHandler.KEY_DOWN -> controller.getAxis(1) > 0.5f
                    InputHandler.KEY_LEFT -> controller.getAxis(0) < -0.5f
                    InputHandler.KEY_RIGHT -> controller.getAxis(0) > 0.5f
                    else -> false
                }
            } else {
                // Standard Controller Mapping (Xbox-like)
                val mapping = controller.mapping
                when (padKey) {
                    InputHandler.KEY_A -> controller.getButton(mapping.buttonB)
                    InputHandler.KEY_B -> controller.getButton(mapping.buttonA)
                    InputHandler.KEY_START -> controller.getButton(mapping.buttonStart)
                    InputHandler.KEY_SELECT -> controller.getButton(mapping.buttonBack)
                    InputHandler.KEY_UP -> controller.getButton(mapping.buttonDpadUp) || controller.getAxis(mapping.axisLeftY) < -0.5f
                    InputHandler.KEY_DOWN -> controller.getButton(mapping.buttonDpadDown) || controller.getAxis(mapping.axisLeftY) > 0.5f
                    InputHandler.KEY_LEFT -> controller.getButton(mapping.buttonDpadLeft) || controller.getAxis(mapping.axisLeftX) < -0.5f
                    InputHandler.KEY_RIGHT -> controller.getButton(mapping.buttonDpadRight) || controller.getAxis(mapping.axisLeftX) > 0.5f
                    else -> false
                }
            }

            return if (isPressed) 0x41 else 0x40
        } catch (e: Exception) {
            e.printStackTrace()
            return 0x40
        }
    }

    override fun setKeyState(keyCode: Int, isPressed: Boolean) {
        // No-op for Gamepad
    }

    fun close() {
        // No explicit close
    }
}

class DummyApplication : Application {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var postRunnableCount = 0

    init {
        println("DummyApplication Initialized")
    }

    override fun getApplicationListener(): ApplicationListener? = null
    override fun getGraphics(): Graphics? = null
    override fun getAudio(): Audio? = null
    override fun getInput(): Input? = null
    override fun getFiles(): Files? = null
    override fun getNet(): Net? = null
    override fun log(tag: String?, message: String?) {}
    override fun log(tag: String?, message: String?, exception: Throwable?) {}
    override fun error(tag: String?, message: String?) {}
    override fun error(tag: String?, message: String?, exception: Throwable?) {}
    override fun debug(tag: String?, message: String?) {}
    override fun debug(tag: String?, message: String?, exception: Throwable?) {}
    override fun setLogLevel(logLevel: Int) {}
    override fun getLogLevel(): Int = 0
    override fun getType(): Application.ApplicationType = Application.ApplicationType.Desktop
    override fun getVersion(): Int = 0
    override fun getJavaHeap(): Long = 0
    override fun getNativeHeap(): Long = 0
    override fun getPreferences(name: String?): Preferences? = null
    override fun getClipboard(): Clipboard? = null

    override fun postRunnable(runnable: Runnable?) {
        postRunnableCount++
        if (postRunnableCount % 60 == 0) { // Log once every ~1 second (assuming 60fps)
             println("DummyApplication: Heartbeat (polling active)")
        }
        runnable?.let {
            executor.schedule(it, 16, TimeUnit.MILLISECONDS)
        }
    }

    override fun exit() {
        executor.shutdown()
    }

    override fun addLifecycleListener(listener: LifecycleListener?) {}
    override fun removeLifecycleListener(listener: LifecycleListener?) {}
    override fun setApplicationLogger(p0: ApplicationLogger?) {}
    override fun getApplicationLogger(): ApplicationLogger? = null
}

fun main() {
    val gamepadController = GamepadController()
    println("--- DIAGNOSTIC MODE ---")
    println(gamepadController.statusMessage)
    println("Press buttons and move sticks to identify IDs...")

    val controllers = Controllers.getControllers()
    if (controllers.size == 0) return

    val controller = controllers.first()
    println("Controller Name: '${controller.name}'")
    println("Button Count: ${controller.minButtonIndex} to ${controller.maxButtonIndex}")
    
    while (true) {
        gamepadController.update()

        // Scan all potential buttons
        val pressed = ArrayList<Int>()
        for (i in 0..100) {
            try {
                if (controller.getButton(i)) pressed.add(i)
            } catch (_: Exception) { }
        }
        if (pressed.isNotEmpty()) {
            println("Buttons Pressed: $pressed")
        }

        for (i in 0..10) {
             try {
                 val axisVal = controller.getAxis(i)
                 if (abs(axisVal) > 0.5) {
                     println("Axis $i: $axisVal")
                 }
             } catch (_: Exception) { }
        }

        try {
            Thread.sleep(50)
        } catch (_: InterruptedException) {
            break
        }
    }
    gamepadController.close()
}
