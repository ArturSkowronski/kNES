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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader
import knes.controllers.DummyApplication
import knes.controllers.GamepadController
import knes.emulator.NES
import knes.emulator.ui.GUIAdapter
import kotlinx.coroutines.delay
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Lwjgl3NativesLoader.load()
    if (Gdx.app == null) {
        Gdx.app = DummyApplication()
    }

    application {
        val windowState = rememberWindowState(width = 800.dp, height = 700.dp)
    var isEmulatorRunning by remember { mutableStateOf(false) }

    val gamepadController = remember { GamepadController() }
    val inputHandler = remember { ComposeInputHandler(gamepadController) }

    val screenView = remember { ComposeScreenView(1) }
    val nes = remember { NES(GUIAdapter(inputHandler, screenView)) }
    val composeUI = remember { ComposeUI(nes, screenView) }
    val focusRequester = remember { FocusRequester() }

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

        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                focusRequester.requestFocus()
            }
        }

        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize().focusRequester(focusRequester).focusable()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally
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
                        Button(onClick = {
                            if (isEmulatorRunning) composeUI.stopEmulator() else composeUI.startEmulator()
                            isEmulatorRunning = !isEmulatorRunning
                            focusRequester.requestFocus()
                        }) { Text(if (isEmulatorRunning) "Stop Emulator" else "Start Emulator") }

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
                            }) {
                            Text("Load ROM")
                        }
                    }
                    Text(
                        text = gamepadController.statusMessage,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Box(
                            modifier = Modifier.weight(1f), contentAlignment = Alignment.Center
                        ) {
                            composeUI.nesScreenRenderer()
                        }
                        Column {
                            Box(
                                modifier = Modifier.weight(1f), contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource("frame.png"),
                                    contentDescription = "NES Frame",
                                    modifier = Modifier.size(256.dp, 240.dp)
                                )
                            }
                            Box(
                                modifier = Modifier.weight(1f), contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource("logo.png"),
                                    contentDescription = "NES Frame",
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
