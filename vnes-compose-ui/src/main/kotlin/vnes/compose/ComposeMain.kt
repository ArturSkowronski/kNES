package vnes.compose

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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import vnes.emulator.NES
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Composable function that renders the NES screen.
 * 
 * @param screenView The ComposeScreenView to render
 */
@Composable
fun NESScreenRenderer(screenView: ComposeScreenView) {
    // State to trigger recomposition when the frame is updated
    var frameCount by remember { mutableStateOf(0) }
    // State to store the current bitmap
    var currentBitmap by remember { mutableStateOf(screenView.getFrameBitmap()) }
    // Get the scale from the screenView
    val scale = screenView.getScale()

    // Calculate the scaled dimensions
    val scaledWidth = 256 * scale
    val scaledHeight = 240 * scale

    // Set up the callback to trigger recomposition when a new frame is ready
    DisposableEffect(Unit) {
        screenView.onFrameReady = {
            // Update the bitmap when a new frame is ready
            currentBitmap = screenView.getFrameBitmap()
            frameCount++
        }

        onDispose {
            screenView.onFrameReady = null
        }
    }

    // Render the frame
    Canvas(
        modifier = Modifier
            .width(scaledWidth.dp)
            .height(scaledHeight.dp)
    ) {
        // Draw the image scaled to fit the canvas
        drawImage(
            image = currentBitmap,
            dstSize = androidx.compose.ui.unit.IntSize(scaledWidth, scaledHeight)
        )

        // This is a workaround to ensure the Canvas is recomposed for each frame
        // by making it depend on the frameCount state variable
        if (frameCount > 0) {
            // Do nothing, this is just to create a dependency on frameCount
        }
    }
}

/**
 * Main entry point for the Compose UI.
 */
fun main() = application {
    val windowState = rememberWindowState(width = 800.dp, height = 600.dp)
    var isEmulatorRunning by remember { mutableStateOf(false) }

    // Create the UI factory and components
    val uiFactory = remember { ComposeUIFactory() }
    val screenView = remember { uiFactory.createScreenView(2) as ComposeScreenView }
    val nes = remember { NES(uiFactory, screenView) }
    val composeUI = remember { uiFactory.getComposeUI() }

    // Initialize the UI with the NES instance and screen view
    LaunchedEffect(Unit) {
        composeUI.init(nes, screenView)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "vNES Emulator",
        state = windowState
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title
                    Text(
                        text = "vNES Emulator - Compose UI1",
                        style = MaterialTheme.typography.h4,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Screen view
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        NESScreenRenderer(screenView)
                    }

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Start/Stop button
                        Button(
                            onClick = {
                                if (isEmulatorRunning) {
                                    composeUI.stopEmulator()
                                } else {
                                    composeUI.startEmulator()
                                }
                                isEmulatorRunning = !isEmulatorRunning
                            }
                        ) {
                            Text(if (isEmulatorRunning) "Stop Emulator" else "Start Emulator")
                        }

                        // Load ROM button
                        Button(
                            onClick = {
                                val fileChooser = JFileChooser()
                                fileChooser.fileFilter = FileNameExtensionFilter("NES ROMs", "nes")
                                if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val file = fileChooser.selectedFile
                                    if (composeUI.loadRom(file.absolutePath)) {
                                        // ROM loaded successfully
                                        if (!isEmulatorRunning) {
                                            composeUI.startEmulator()
                                            isEmulatorRunning = true
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Load ROM")
                        }
                    }
                }
            }
        }
    }
}
