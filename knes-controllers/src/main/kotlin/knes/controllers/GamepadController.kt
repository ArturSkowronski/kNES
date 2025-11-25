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
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader
import com.badlogic.gdx.controllers.Controllers
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerAdapter
import com.badlogic.gdx.utils.Array
import knes.controllers.helpers.JoyConInitializer
import knes.controllers.helpers.MacOsPermissionHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class GamepadController : ControllerProvider {

    private var controllers: Array<Controller>? = null
    private var leftJoyCon: Controller? = null
    private var rightJoyCon: Controller? = null

    var statusMessage: String = "Initializing..."
        private set

    init {
        try {
            Lwjgl3NativesLoader.load()

            if (Gdx.app == null) {
                Gdx.app = GDXApplication()
            }
            refreshControllers()
        } catch (e: Exception) {
            e.printStackTrace()
            statusMessage = "No controllers detected"
            controllers = null
        }
    }

    private fun refreshControllers() {
        controllers = Controllers.getControllers()
        if (controllers == null || controllers!!.size == 0) {
            statusMessage = "No controllers found"
            leftJoyCon = null
            rightJoyCon = null
        } else {
            leftJoyCon = controllers?.firstOrNull { it.name.contains("Joy-Con (L)", ignoreCase = true) }
            rightJoyCon = controllers?.firstOrNull { it.name.contains("Joy-Con (R)", ignoreCase = true) }

            val names = controllers!!.joinToString { it.name }
            statusMessage = if (leftJoyCon != null && rightJoyCon != null) {
                "Paired Joy-Cons detected. Controllers: $names"
            } else {
                "Controllers: $names"
            }
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

            // Dual Joy-Con Mode
            if (leftJoyCon != null && rightJoyCon != null) {
                val isPressed = when (padKey) {
                    // Right Joy-Con for Actions
                    // A (East) -> Button 0 or 1 or 2 or 3? 
                    // Based on Xbox layout: A=0, B=1, X=2, Y=3.
                    // If Joy-Con follows standard layout: B=0, A=1, Y=2, X=3?
                    // Let's try standard SDL layout: 0=A(South/B), 1=B(East/A), 2=X(West/Y), 3=Y(North/X)
                    // Wait, SDL GameController: A=0 (South), B=1 (East), X=2 (West), Y=3 (North).
                    // Nintendo Layout: B is South, A is East.
                    // So B -> 0, A -> 1.
                    InputHandler.KEY_A -> rightJoyCon!!.getButton(1) // East (A)
                    InputHandler.KEY_B -> rightJoyCon!!.getButton(0) // South (B)
                    InputHandler.KEY_START -> rightJoyCon!!.getButton(9) // Plus -> Start or 9/10?
                    
                    // Left Joy-Con for Movement & Select
                    InputHandler.KEY_SELECT -> leftJoyCon!!.getButton(8) // Minus -> Select (often 8)
                    
                    // D-Pad / Analog Stick
                    // Stick is usually Axis 0 (X) and 1 (Y).
                    // D-Buttons on L:
                    // If mapped as buttons: 0 (Down/Left?), 1 (Right/Down?), 2 (Left/Up?), 3 (Up/Right?)
                    // Let's rely on Axis first as it's more reliable for movement
                    InputHandler.KEY_UP -> leftJoyCon!!.getAxis(1) < -0.5f || leftJoyCon!!.getButton(2)
                    InputHandler.KEY_DOWN -> leftJoyCon!!.getAxis(1) > 0.5f || leftJoyCon!!.getButton(1)
                    InputHandler.KEY_LEFT -> leftJoyCon!!.getAxis(0) < -0.5f || leftJoyCon!!.getButton(0)
                    InputHandler.KEY_RIGHT -> leftJoyCon!!.getAxis(0) > 0.5f || leftJoyCon!!.getButton(3)
                    else -> false
                }
                return if (isPressed) 0x41 else 0x40
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
    }

    fun close() {
    }
}

class GDXApplication : Application {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var postRunnableCount = 0

    init {
        println("GDXApplication Initialized")
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
             println("GDXApplication: Heartbeat (polling active)")
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
    // 1. macOS Permissions
    println("--- Checking macOS Permissions ---")
    val hasPermission = MacOsPermissionHelper.checkAndRequestInputMonitoring()
    if (!hasPermission) {
        println("WARNING: Input Monitoring permission missing or denied. Joy-Cons may not work.")
    }

    // 2. Joy-Con Handshake
    println("--- Initializing Joy-Cons (HID Handshake) ---")
    try {
        JoyConInitializer.initializeJoyCons()
    } catch (e: Throwable) {
        println("Failed to initialize Joy-Cons via HID: ${e.message}")
        e.printStackTrace()
    }

    val gamepadController = GamepadController()
    println("--- DIAGNOSTIC MODE ---")
    println(gamepadController.statusMessage)
    println("Press buttons and move sticks on ANY controller to identify IDs...")

    val controllers = Controllers.getControllers()
    if (controllers.size == 0) return

    for (controller in controllers) {
        println("Controller Found: '${controller.name}'")
        println("  Buttons: ${controller.minButtonIndex} to ${controller.maxButtonIndex}")
    }
    
    // Add global listener to catch events
    Controllers.addListener(object : ControllerAdapter() {
        override fun connected(controller: Controller) {
            println("Connected: ${controller.name}")
        }

        override fun disconnected(controller: Controller) {
            println("Disconnected: ${controller.name}")
        }

        override fun buttonDown(controller: Controller, buttonCode: Int): Boolean {
            println("[LISTENER] ${controller.name} Button DOWN: $buttonCode")
            return false
        }

        override fun buttonUp(controller: Controller, buttonCode: Int): Boolean {
            println("[LISTENER] ${controller.name} Button UP: $buttonCode")
            return false
        }

        override fun axisMoved(controller: Controller, axisCode: Int, value: Float): Boolean {
            if (abs(value) > 0.2) {
                println("[LISTENER] ${controller.name} Axis $axisCode: $value")
            }
            return false
        }
    })

    while (true) {
        gamepadController.update()

        // Also poll just in case listener doesn't work (which would be weird, but for completeness)
        for (controller in controllers) {
             // Scan Axes (polling)
            for (i in 0..10) {
                 try {
                     val axisVal = controller.getAxis(i)
                     if (abs(axisVal) > 0.2) {
                         // println("[POLL] ${controller.name} Axis $i: $axisVal") 
                     }
                 } catch (_: Exception) { }
            }
        }

        try {
            Thread.sleep(50)
        } catch (_: InterruptedException) {
            break
        }
    }
    gamepadController.close()
}
