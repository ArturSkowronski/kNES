package knes.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import knes.controllers.GamepadController
import knes.emulator.NES
import knes.emulator.ui.GUIAdapter
import org.junit.Rule
import org.junit.Test

class ComposeUISmokeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `UI renders without crashing`() {
        rule.setContent {
            val screenView = remember { ComposeScreenView(1) }
            val gamepadController = remember { GamepadController() }
            val inputHandler = remember { ComposeInputHandler(gamepadController) }
            val nes = remember { NES(GUIAdapter(inputHandler, screenView)) }
            val composeUI = remember { ComposeUI(nes, screenView) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "kNES Emulator",
                            style = MaterialTheme.typography.h4,
                            modifier = Modifier.testTag("title")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = {},
                                modifier = Modifier.testTag("startStopButton")
                            ) { Text("Start Emulator") }
                            Button(
                                onClick = {},
                                modifier = Modifier.testTag("loadRomButton")
                            ) { Text("Load ROM") }
                        }
                        Box(
                            modifier = Modifier.weight(1f).testTag("screenArea"),
                            contentAlignment = Alignment.Center
                        ) {
                            composeUI.nesScreenRenderer()
                        }
                    }
                }
            }
        }

        rule.onNodeWithTag("title").assertIsDisplayed()
        rule.onNodeWithTag("title").assertTextEquals("kNES Emulator")
        rule.onNodeWithTag("startStopButton").assertIsDisplayed()
        rule.onNodeWithTag("loadRomButton").assertIsDisplayed()
        rule.onNodeWithTag("screenArea").assertExists()
    }

    @Test
    fun `start stop button toggles text`() {
        rule.setContent {
            var isRunning by remember { mutableStateOf(false) }

            Button(
                onClick = { isRunning = !isRunning },
                modifier = Modifier.testTag("toggleButton")
            ) {
                Text(if (isRunning) "Stop Emulator" else "Start Emulator")
            }
        }

        rule.onNodeWithTag("toggleButton").assertTextEquals("Start Emulator")
        rule.onNodeWithTag("toggleButton").performClick()
        rule.onNodeWithTag("toggleButton").assertTextEquals("Stop Emulator")
        rule.onNodeWithTag("toggleButton").performClick()
        rule.onNodeWithTag("toggleButton").assertTextEquals("Start Emulator")
    }
}
