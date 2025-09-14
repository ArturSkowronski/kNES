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

package knes.skiko

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

import knes.emulator.NES
import knes.emulator.ui.GUIAdapter
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoView
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

/**
 * Main entry point for the Skiko UI.
 */
fun main() {
    SwingUtilities.invokeLater {
        SkikoMain().start()
    }
}

/**
 * Main class for the Skiko UI implementation.
 */
class SkikoMain {
    val inputHandler = SkikoInputHandler()
    val screenView = SkikoScreenView(1)

    private val nes = NES(GUIAdapter(inputHandler, screenView))
    private val skikoUI = SkikoUI(nes, screenView)

    private var isEmulatorRunning = false
    private val renderExecutor = Executors.newSingleThreadScheduledExecutor()

    /**
     * Starts the application.
     */
    fun start() {
        val frame = JFrame("kNES Emulator - Skiko UI")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.layout = BorderLayout()

        // Add a window listener to clean up resources when the window is closed
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                renderExecutor.shutdown()
                skikoUI.destroy()
                exitProcess(0)
            }
        })

        // Create the title label
        val titleLabel = JLabel("kNES Emulator - Skiko UI")
        titleLabel.font = Font("Arial", Font.BOLD, 24)
        titleLabel.horizontalAlignment = JLabel.CENTER
        frame.add(titleLabel, BorderLayout.NORTH)

        // Create the Skia layer for rendering
        val skiaLayer = SkiaLayer()
        skiaLayer.attachTo(frame.contentPane)
        skiaLayer.preferredSize = Dimension(512, 480)

        // Create a SkikoView for rendering
        val skikoView = object : SkikoView {
            private var frameCount = 0

            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                frameCount++

                // Clear the canvas
                canvas.clear(0xFF333333.toInt())

                // Get the frame bitmap from the screen view
                val frameBitmap = screenView.getFrameBitmap()

                // Convert Bitmap to Image for drawing
                val frameImage = Image.makeFromBitmap(frameBitmap)

                // Calculate scaling to maintain aspect ratio
                val srcWidth = frameBitmap.width.toFloat()
                val srcHeight = frameBitmap.height.toFloat()
                val dstWidth = width.toFloat()
                val dstHeight = height.toFloat()

                val scale = minOf(dstWidth / srcWidth, dstHeight / srcHeight)
                val scaledWidth = srcWidth * scale
                val scaledHeight = srcHeight * scale

                // Center the image in the canvas
                val offsetX = (dstWidth - scaledWidth) / 2
                val offsetY = (dstHeight - scaledHeight) / 2

                // Debug logging (every 60 frames to avoid spamming)
                if (frameCount % 60 == 0) {
                    println("[DEBUG] Skiko Renderer: src=${srcWidth}x${srcHeight}, dst=${dstWidth}x${dstHeight}, scale=$scale, scaled=${scaledWidth}x${scaledHeight}, offset=($offsetX,$offsetY)")
                }

                // Draw the image with scaling
                val paint = Paint()
                canvas.drawImageRect(
                    frameImage,
                    Rect(0f, 0f, srcWidth, srcHeight),
                    Rect(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight),
                    paint
                )
            }
        }

        // Set the view on the layer
        skiaLayer.skikoView = skikoView

        // Add the Skia layer to the frame
        frame.add(skiaLayer, BorderLayout.CENTER)

        // Create the control panel
        val controlPanel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 10))

        // Create the Start/Stop button
        val startStopButton = JButton("Start Emulator")
        startStopButton.addActionListener {
            if (isEmulatorRunning) {
                skikoUI.stopEmulator()
                startStopButton.text = "Start Emulator"
            } else {
                skikoUI.startEmulator()
                startStopButton.text = "Stop Emulator"
            }
            isEmulatorRunning = !isEmulatorRunning
        }
        controlPanel.add(startStopButton)

        // Create the Load ROM button
        val loadRomButton = JButton("Load ROM")
        loadRomButton.addActionListener {
            val fileChooser = JFileChooser()
            fileChooser.fileFilter = FileNameExtensionFilter("NES ROMs", "nes")
            if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                val file = fileChooser.selectedFile
                if (skikoUI.loadRom(file.absolutePath)) {
                    // ROM loaded successfully
                    if (!isEmulatorRunning) {
                        skikoUI.startEmulator()
                        startStopButton.text = "Stop Emulator"
                        isEmulatorRunning = true
                    }
                }
            }
        }
        controlPanel.add(loadRomButton)

        // Add the control panel to the frame
        frame.add(controlPanel, BorderLayout.SOUTH)

        // Set up the frame
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        // Request focus for keyboard input
        skiaLayer.isFocusable = true
        skiaLayer.requestFocus()

        inputHandler.registerKeyAdapter(skiaLayer)

        // Set the callback for when a new frame is ready
        screenView.onFrameReady = {
            SwingUtilities.invokeLater {
                skiaLayer.needRedraw()
            }
        }

        // Start a timer to trigger redraws (as a fallback)
        renderExecutor.scheduleAtFixedRate({
            SwingUtilities.invokeLater {
                skiaLayer.needRedraw()
            }
        }, 0, 16, TimeUnit.MILLISECONDS) // ~60fps
    }
}
