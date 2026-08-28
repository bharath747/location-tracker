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
fun AdminScreen(
    devices: List<AdminDevice>, status: String, onRefresh: () -> Unit,
    onFetch: (String) -> Unit, onFetchStatus: (String) -> Unit,
    onRing: (String) -> Unit, onStop: (String) -> Unit,
    onInterval: (Pair<String, Int>) -> Unit, onMap: (AdminDevice) -> Unit,
    onRemove: (String) -> Unit, onBack: () -> Unit
) {
    var interval by remember { mutableStateOf("15") }
    var deviceToRemove by remember { mutableStateOf<AdminDevice?>(null) }
    var expandedDeviceId by remember { mutableStateOf<String?>(null) }
    deviceToRemove?.let { device -> AlertDialog(onDismissRequest = { deviceToRemove = null }, title = { Text("Remove device?") }, text = { Text("Remove ${device.name} from your device list?") }, confirmButton = { Button(onClick = { onRemove(device.id); deviceToRemove = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deviceToRemove = null }) { Text("Cancel") } }) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Device Management", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); TextButton(onClick = onBack) { Text("Tracker") } } }
        item { Text(status); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${devices.size} device(s)"); Button(onClick = onRefresh) { Text("Refresh") } }; OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit) }, label = { Text("Tracking interval (min)") }, modifier = Modifier.fillMaxWidth()) }
        items(devices) { d -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Battery: ${d.battery}")
            Text(if (d.latitude != null && d.longitude != null) "Location available" else "Location unavailable")
            Text("Last location fetch: ${d.locationFetchedAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Last device status: ${d.statusFetchedAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onFetch(d.id) }, modifier = Modifier.weight(1f)) { Text("Locate") }; OutlinedButton(onClick = { onFetchStatus(d.id) }, modifier = Modifier.weight(1f)) { Text("Device Status") } }
            Button(onClick = { expandedDeviceId = if (expandedDeviceId == d.id) null else d.id }, modifier = Modifier.fillMaxWidth()) { Text(if (expandedDeviceId == d.id) "Hide Device Info" else "View Device Info") }
            if (expandedDeviceId == d.id) {
                HorizontalDivider()
                Text("Device Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (d.statusFetchedAt == "Not available") Text("No device status fetched yet. Tap Device Status, wait for command execution, then Refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.statusDetails.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { onRing(d.id) }, modifier = Modifier.weight(1f)) { Text("Ring") }; OutlinedButton(onClick = { onStop(d.id) }, modifier = Modifier.weight(1f)) { Text("Stop Ring") } }
            if (d.latitude != null && d.longitude != null) OutlinedButton(onClick = { onMap(d) }, modifier = Modifier.fillMaxWidth()) { Text("View Map") }
            Button(onClick = { onInterval(d.id to (interval.toIntOrNull() ?: 15)) }, modifier = Modifier.fillMaxWidth()) { Text("Apply Tracking Interval") }
            TextButton(onClick = { deviceToRemove = d }, modifier = Modifier.align(Alignment.End)) { Text("Remove Device", color = MaterialTheme.colorScheme.error) }
        } } }
    }
}
