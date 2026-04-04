package knes.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import knes.debug.GameProfile
import knes.debug.MemoryMonitor
import knes.emulator.NES
import kotlinx.coroutines.delay

@Composable
fun ProfileMonitorWindow(
    nes: NES,
    onClose: () -> Unit
) {
    val monitor = remember { MemoryMonitor() }
    val profiles = remember { GameProfile.list() }
    var selectedProfile by remember { mutableStateOf(profiles.firstOrNull()) }
    var values by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProfile) {
        selectedProfile?.let { monitor.applyProfile(it) }
    }

    // Auto-refresh values from NES memory
    LaunchedEffect(selectedProfile) {
        while (true) {
            if (monitor.activeProfile != null) {
                values = monitor.read(nes.cpuMemory)
            }
            delay(100) // ~10fps refresh
        }
    }

    Window(
        onCloseRequest = onClose,
        title = "Profile Monitor — ${selectedProfile?.name ?: "No profile"}",
        resizable = true
    ) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // Profile selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("Profile: ", style = MaterialTheme.typography.subtitle1)

                    Box {
                        Button(onClick = { expanded = true }) {
                            Text(selectedProfile?.name ?: "Select...")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(onClick = {
                                    selectedProfile = profile
                                    expanded = false
                                }) {
                                    Text("${profile.name} (${profile.addresses.size} vars)")
                                }
                            }
                        }
                    }
                }

                Divider()

                if (selectedProfile == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a game profile to start monitoring")
                    }
                } else {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Variable", style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f))
                        Text("Value", style = MaterialTheme.typography.caption, modifier = Modifier.width(60.dp))
                        Text("Hex", style = MaterialTheme.typography.caption, modifier = Modifier.width(60.dp))
                        Text("Address", style = MaterialTheme.typography.caption, modifier = Modifier.width(70.dp))
                    }

                    Divider()

                    // Values table
                    val entries = selectedProfile!!.addresses.entries.toList()
                    LazyColumn {
                        items(entries) { (name, entry) ->
                            val value = values[name] ?: 0
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    name,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "$value",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(60.dp)
                                )
                                Text(
                                    "0x${value.toString(16).uppercase().padStart(2, '0')}",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(60.dp)
                                )
                                Text(
                                    "0x${entry.address.toString(16).uppercase().padStart(4, '0')}",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(70.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
