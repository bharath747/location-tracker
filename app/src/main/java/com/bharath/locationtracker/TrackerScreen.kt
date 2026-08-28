package com.bharath.locationtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class AdminDevice(
    val id: String,
    val name: String,
    val battery: String,
    val latitude: Double?,
    val longitude: Double?,
    val locationFetchedAt: String = "Not available"
)

@Composable
fun RegistrationScreen(onRegister: (String) -> Unit, status: String) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Location Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Give this device a unique name", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button({ onRegister(name) }, modifier = Modifier.fillMaxWidth()) { Text("Register Device") }
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    } }
}

@Composable
fun TrackerScreen(status: String, location: String, deviceId: String, tracking: Boolean, alarm: String, listener: String, lastCommand: String, commandResult: String, onGrant: () -> Unit, onFetch: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onRing: () -> Unit, onStopAlarm: () -> Unit, onAdmin: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Location Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(deviceId, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = onAdmin) { Text("Manage") } } }
        item { SectionCard("Status") { Text(if (tracking) "Tracking active" else "Tracking paused"); Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { SectionCard("Current location") { Text(location, color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onFetch, Modifier.fillMaxWidth()) { Text("Locate Now") }; TextButton(onGrant) { Text("Location Permission") } } }
        item { SectionCard("Background tracking") { Button(if (tracking) onStop else onStart, Modifier.fillMaxWidth()) { Text(if (tracking) "Stop Tracking" else "Start Tracking") } } }
        item { SectionCard("Remote status") { Text("Listener: $listener"); Text("Last command: $lastCommand", style = MaterialTheme.typography.bodySmall); Text("Result: $commandResult", style = MaterialTheme.typography.bodySmall) } }
        item { SectionCard("Alarm") { Text("Status: $alarm"); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onRing, Modifier.weight(1f)) { Text("Test Ring") }; OutlinedButton(onStopAlarm, Modifier.weight(1f)) { Text("Stop") } } } }
    }
}
