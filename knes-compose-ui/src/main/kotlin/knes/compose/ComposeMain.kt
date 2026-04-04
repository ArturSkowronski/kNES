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

package knes.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import knes.api.EmbeddedApiServer
import knes.controllers.GamepadController
import knes.emulator.NES
import knes.emulator.ui.GUIAdapter
import java.awt.FileDialog
import java.awt.Frame

@Composable
private fun classpathPainter(path: String): BitmapPainter {
    return remember(path) {
        val bytes = object {}.javaClass.classLoader.getResourceAsStream(path)!!.readAllBytes()
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }
}

fun main() {
    application {
        val windowState = rememberWindowState(width = 800.dp, height = 700.dp)
        var isEmulatorRunning by remember { mutableStateOf(false) }

        val gamepadController = remember { GamepadController() }
        val inputHandler = remember { ComposeInputHandler(gamepadController) }

        val screenView = remember { ComposeScreenView(1) }
        val nes = remember { NES(GUIAdapter(inputHandler, screenView)) }
        val composeUI = remember { ComposeUI(nes, screenView) }
        val focusRequester = remember { FocusRequester() }
        var showMonitor by remember { mutableStateOf(false) }

        val apiServer = remember { EmbeddedApiServer(nes) }
        var apiRunning by remember { mutableStateOf(false) }

        // Feed frame buffer to the shared API session so /screen works
        LaunchedEffect(apiRunning) {
            if (apiRunning) {
                screenView.onApiFrameCallback = { buffer ->
                    apiServer.session.updateFrameBuffer(buffer)
                }
            } else {
                screenView.onApiFrameCallback = null
            }
        }

        // Clean up API server on exit
        DisposableEffect(Unit) {
            onDispose {
                if (apiServer.isRunning) apiServer.stop()
            }
        }

        if (showMonitor) {
            ProfileMonitorWindow(nes = nes, onClose = { showMonitor = false })
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "kNES Emulator",
            state = windowState,
            onKeyEvent = inputHandler::keyEventHandler,
            focusable = true
        ) {
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().focusRequester(focusRequester).focusable()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "kNES Emulator",
                            style = MaterialTheme.typography.h4,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(onClick = {
                                if (isEmulatorRunning) composeUI.stopEmulator() else composeUI.startEmulator()
                                isEmulatorRunning = !isEmulatorRunning
                                focusRequester.requestFocus()
                            }) {
                                Text(if (isEmulatorRunning) "Stop Emulator" else "Start Emulator")
                            }

                            Button(onClick = {
                                showMonitor = !showMonitor
                                focusRequester.requestFocus()
                            }) {
                                Text(if (showMonitor) "Hide Monitor" else "Monitor")
                            }

                            Button(onClick = {
                                if (apiRunning) {
                                    apiServer.stop()
                                    apiRunning = false
                                } else {
                                    apiServer.start()
                                    apiRunning = true
                                }
                                focusRequester.requestFocus()
                            }) {
                                Text(if (apiRunning) "API :6502 ON" else "API Server")
                            }

                            Button(onClick = {
                                val dialog = FileDialog(null as Frame?, "Load NES ROM", FileDialog.LOAD)
                                dialog.setFilenameFilter { _, name -> name.endsWith(".nes") }
                                dialog.isVisible = true
                                val dir = dialog.directory
                                val file = dialog.file
                                if (dir != null && file != null) {
                                    if (composeUI.loadRom(dir + file)) {
                                        if (!isEmulatorRunning) {
                                            composeUI.startEmulator()
                                            isEmulatorRunning = true
                                        }
                                    }
                                }
                                focusRequester.requestFocus()
                            }) {
                                Text("Load ROM")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                composeUI.nesScreenRenderer()
                            }
                            Column {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = classpathPainter("frame.png"),
                                        contentDescription = "NES Frame",
                                        modifier = Modifier.size(256.dp, 240.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = classpathPainter("logo.png"),
                                        contentDescription = "kNES Logo",
                                        modifier = Modifier.size(256.dp, 240.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
