package com.bharath.locationtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AdminScreen(devices: List<AdminDevice>, status: String, onRefresh: () -> Unit, onFetch: (String) -> Unit, onRing: (String) -> Unit, onStop: (String) -> Unit, onInterval: (Pair<String, Int>) -> Unit, onMap: (AdminDevice) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    var interval by remember { mutableStateOf("15") }
    var deviceToRemove by remember { mutableStateOf<AdminDevice?>(null) }
    deviceToRemove?.let { device ->
        AlertDialog(onDismissRequest = { deviceToRemove = null }, title = { Text("Remove device?") }, text = { Text("Remove ${device.name} from your device list?") }, confirmButton = { Button({ onRemove(device.id); deviceToRemove = null }) { Text("Remove") } }, dismissButton = { TextButton({ deviceToRemove = null }) { Text("Cancel") } })
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Device Management", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); TextButton(onBack) { Text("Tracker") } } }
        item { Text(status); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${devices.size} device(s)"); Button(onRefresh) { Text("Refresh") } }; OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Tracking interval (min)") }, modifier = Modifier.fillMaxWidth()) }
        items(devices) { d -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Battery: ${d.battery}")
            Text(if (d.latitude != null && d.longitude != null) "Location available" else "Location unavailable")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ onFetch(d.id) }, Modifier.weight(1f)) { Text("Locate") }; OutlinedButton({ onRing(d.id) }, Modifier.weight(1f)) { Text("Ring") } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ onStop(d.id) }, Modifier.weight(1f)) { Text("Stop Ring") }; if (d.latitude != null && d.longitude != null) OutlinedButton({ onMap(d) }, Modifier.weight(1f)) { Text("View Map") } }
            Button({ onInterval(d.id to (interval.toIntOrNull() ?: 15)) }, Modifier.fillMaxWidth()) { Text("Apply Tracking Interval") }
            TextButton({ deviceToRemove = d }, Modifier.align(Alignment.End)) { Text("Remove Device", color = MaterialTheme.colorScheme.error) }
        } }
    }
}
