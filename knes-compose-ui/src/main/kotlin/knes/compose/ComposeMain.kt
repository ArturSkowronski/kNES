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
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import knes.controllers.KeyboardController
import kotlinx.coroutines.delay
import knes.emulator.NES
import java.awt.event.KeyEvent
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun nesScreenRenderer(screenView: ComposeScreenView) {
    var frameCount by remember { mutableStateOf(0) }
    var currentBitmap by remember { mutableStateOf(screenView.getFrameBitmap()) }
    val baseScale = screenView.getScale()
    val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    val scale = if (isMacOS) baseScale * 2 else baseScale

    val scaledWidth = 512 * scale 
    val scaledHeight = 480 * scale

    DisposableEffect(Unit) {
        screenView.onFrameReady = {
            currentBitmap = screenView.getFrameBitmap()
            frameCount++
        }

        onDispose {
            screenView.onFrameReady = null
        }
    }

    Canvas(
        modifier = Modifier
            .width(scaledWidth.dp)
            .height(scaledHeight.dp)
    ) {
        drawImage(
            image = currentBitmap,
            dstSize = IntSize(scaledWidth, scaledHeight)
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val windowState = rememberWindowState(width = 800.dp, height = 700.dp)
    var isEmulatorRunning by remember { mutableStateOf(false) }
    val controller = remember { KeyboardController() }

    val uiFactory = remember { ComposeUIFactory(controller) }
    val screenView = remember { uiFactory.screenView as ComposeScreenView }

    val nes = remember { NES(uiFactory, screenView) }
    val composeUI = remember { uiFactory.composeUI }

    LaunchedEffect(Unit) {
        composeUI.init(nes, screenView)
        composeUI.setInputHandler(uiFactory.inputHandler)
    }

    fun mapKeyCode(key: Key): Int {
        return when (key) {
            Key.Z -> KeyEvent.VK_Z
            Key.X -> KeyEvent.VK_X
            Key.Spacebar -> KeyEvent.VK_ENTER
            Key.V -> KeyEvent.VK_SPACE
            Key.DirectionUp -> KeyEvent.VK_UP
            Key.DirectionDown -> KeyEvent.VK_DOWN
            Key.DirectionLeft -> KeyEvent.VK_LEFT
            Key.DirectionRight -> KeyEvent.VK_RIGHT
            else -> 0
        }
    }

    // Define a function to map AWT key codes to their names
    fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.VK_Z -> "Z"
            KeyEvent.VK_X -> "X"
            KeyEvent.VK_ENTER -> "ENTER"
            KeyEvent.VK_SPACE -> "SPACE"
            KeyEvent.VK_UP -> "UP"
            KeyEvent.VK_DOWN -> "DOWN"
            KeyEvent.VK_LEFT -> "LEFT"
            KeyEvent.VK_RIGHT -> "RIGHT"
            else -> "UNKNOWN"
        }
    }

    val focusRequester = remember { FocusRequester() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "kNES Emulator",
        state = windowState,
        onKeyEvent = { event ->
            val keyCode = if (event.key == Key.Enter) {
                KeyEvent.VK_ENTER
            } else {
                mapKeyCode(event.key)
            }

            if (keyCode != 0) {
                System.out.println("Key event: ${event.type} ${event.key} keyCode: $keyCode (${getKeyName(keyCode)})")

                when (event.type) {
                    KeyEventType.KeyDown -> {
                        composeUI.inputHandler!!.setKeyState(keyCode, true)
                        true
                    }
                    KeyEventType.KeyUp -> {
                        composeUI.inputHandler!!.setKeyState(keyCode, false)
                        true
                    }
                    else -> true  // Always consume key events
                }
            } else {
                false
            }
        },
        focusable = true  // Make the window focusable
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        LaunchedEffect(Unit) {
            while (true) {
                delay(1000) // Request focus every second
                focusRequester.requestFocus()
            }
        }

        MaterialTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "kNES Emulator 🎮",
                        style = MaterialTheme.typography.h4,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                if (isEmulatorRunning) {
                                    composeUI.stopEmulator()
                                } else {
                                    composeUI.startEmulator()
                                }
                                isEmulatorRunning = !isEmulatorRunning
                                // Request focus after button click
                                focusRequester.requestFocus()
                            }
                        ) {
                            Text(if (isEmulatorRunning) "Stop Emulator" else "Start Emulator")
                        }

                        Button(
                            onClick = {
                                val fileChooser = JFileChooser()
                                fileChooser.fileFilter = FileNameExtensionFilter("NES ROMs", "nes")
                                if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val file = fileChooser.selectedFile
                                    if (composeUI.loadRom(file.absolutePath)) {
                                        if (!isEmulatorRunning) {
                                            composeUI.startEmulator()
                                            isEmulatorRunning = true
                                        }
                                    }
                                }
                                focusRequester.requestFocus()
                            }
                        ) {
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
                            nesScreenRenderer(screenView)
                        }
                        Column {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource("frame.png"),
                                contentDescription = "NES Frame",
                                modifier = Modifier.size(256.dp, 240.dp)
                            )
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource("logo.png"),
                                contentDescription = "NES Frame",
                                modifier = Modifier.size(256.dp, 240.dp)
                            )
                        }}

                    }
                }
            }
        }
    }
}
