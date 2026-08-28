package com.bharath.locationtracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

data class AdminDevice(val id: String, val name: String, val model: String, val battery: String, val location: String, val latitude: Double? = null, val longitude: Double? = null)

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Connecting to Firebase...")
    private var locationText by mutableStateOf("No location fetched yet")
    private var deviceId by mutableStateOf("")
    private var trackingActive by mutableStateOf(false)
    private var alarmStatus by mutableStateOf("IDLE")
    private var adminMode by mutableStateOf(false)
    private var adminDevices by mutableStateOf<List<AdminDevice>>(emptyList())
    private var adminStatus by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true || p[Manifest.permission.ACCESS_COARSE_LOCATION] == true) fetchAndUploadLocation() else status = "Location permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = getTrackerDeviceId()
        trackingActive = getSharedPreferences("tracker", Context.MODE_PRIVATE).getBoolean("trackingEnabled", false)
        signInAndRegister()
        if (trackingActive) restoreTrackingIfPossible()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (adminMode) AdminScreen(adminDevices, adminStatus, { loadDevices() }, { sendCommand(it, "FETCH_LOCATION") }, { sendCommand(it, "RING") }, { sendCommand(it, "STOP_RING") }, { updateInterval(it.first, it.second) }, { openGoogleMaps(it) }, { removeDevice(it) }, { adminMode = false })
                    else TrackerScreen(status, locationText, deviceId, trackingActive, alarmStatus, TrackerRuntimeStatus.commandListenerStatus, TrackerRuntimeStatus.lastCommand, TrackerRuntimeStatus.lastCommandResult, { requestLocationPermission() }, { fetchAndUploadLocation() }, { startTracking() }, { stopTracking() }, { testRing() }, { stopAlarm() }, { adminMode = true; loadDevices() })
                }
            }
        }
    }

    private fun getTrackerDeviceId(): String { val p = getSharedPreferences("tracker", Context.MODE_PRIVATE); return p.getString("deviceId", null) ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).also { p.edit().putString("deviceId", it).apply() } }
    private fun signInAndRegister() { val a = FirebaseAuth.getInstance(); if (a.currentUser != null) registerDevice() else a.signInAnonymously().addOnSuccessListener { registerDevice() }.addOnFailureListener { status = "Firebase login failed: ${it.message}" } }
    private fun registerDevice() { val d = hashMapOf<String, Any?>("deviceId" to deviceId, "firebaseUid" to FirebaseAuth.getInstance().currentUser?.uid, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}", "androidVersion" to Build.VERSION.RELEASE, "lastSeen" to FieldValue.serverTimestamp()); FirebaseFirestore.getInstance().collection("devices").document(deviceId).set(d).addOnSuccessListener { status = "Device connected and ready" }.addOnFailureListener { status = "Registration failed: ${it.message}" } }
    private fun restoreTrackingIfPossible() { val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; if (granted) try { ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java)); status = "Restoring background tracking..." } catch (e: Exception) { trackingActive = false; status = "Could not restore tracking: ${e.message}" } else { trackingActive = false; getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putBoolean("trackingEnabled", false).apply(); status = "Tracking paused: location permission required" } }
    private fun startTracking() { val g = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; if (!g) { status = "Grant location permission first"; return }; getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putBoolean("trackingEnabled", true).apply(); ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java)); trackingActive = true; status = "Background tracking is active" }
    private fun stopTracking() { getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putBoolean("trackingEnabled", false).apply(); stopService(Intent(this, TrackingService::class.java)); trackingActive = false; status = "Background tracking stopped" }
    private fun testRing() { try { ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java)); alarmStatus = "RINGING"; status = "Alarm started" } catch (e: Exception) { status = "Alarm error: ${e.message}" } }
    private fun stopAlarm() { stopService(Intent(this, AlarmService::class.java)); alarmStatus = "IDLE"; status = "Alarm stopped" }
    private fun requestLocationPermission() { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    private fun fetchAndUploadLocation() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { status = "Please grant location permission"; return }; status = "Fetching current location..."; lifecycleScope.launch { try { locationText = LocationRepository.fetchAndUpload(this@MainActivity); status = "Location updated successfully" } catch (e: Exception) { status = "Location error: ${e.message}" } } }
    private fun loadDevices() { adminStatus = "Loading devices..."; FirebaseFirestore.getInstance().collection("devices").get().addOnSuccessListener { q -> adminDevices = q.documents.map { d -> val lat = (d.get("latitude") as? Number)?.toDouble(); val lon = (d.get("longitude") as? Number)?.toDouble(); AdminDevice(d.id, d.getString("deviceName") ?: d.id, d.getString("model") ?: "", d.get("batteryLevel")?.toString() ?: "Unknown", if (lat != null && lon != null) "$lat, $lon" else "Location unavailable", lat, lon) }; adminStatus = "${adminDevices.size} device(s) available" }.addOnFailureListener { adminStatus = "Load failed: ${it.message}" } }
    private fun openGoogleMaps(device: AdminDevice) { val lat = device.latitude; val lon = device.longitude; if (lat == null || lon == null) { adminStatus = "No location available for ${device.name}"; return }; try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon"))); adminStatus = "Opening location" } catch (e: Exception) { adminStatus = "Could not open map: ${e.message}" } }
    private fun removeDevice(id: String) { if (id == deviceId) { adminStatus = "Cannot remove this device while the tracker app is running"; return }; adminStatus = "Removing device..."; FirebaseFirestore.getInstance().collection("devices").document(id).delete().addOnSuccessListener { adminDevices = adminDevices.filterNot { it.id == id }; adminStatus = "Device removed successfully" }.addOnFailureListener { adminStatus = "Remove failed: ${it.message}" } }
    private fun sendCommand(id: String, action: String) { adminStatus = "Sending command..."; FirebaseFirestore.getInstance().collection("devices").document(id).collection("commands").add(mapOf("action" to action, "status" to "PENDING", "createdAt" to FieldValue.serverTimestamp())).addOnSuccessListener { adminStatus = "$action command sent" }.addOnFailureListener { adminStatus = "Command failed: ${it.message}" } }
    private fun updateInterval(id: String, minutes: Int) { FirebaseFirestore.getInstance().collection("devices").document(id).collection("config").document("tracking").set(mapOf("intervalMinutes" to minutes.coerceAtLeast(15), "updatedAt" to FieldValue.serverTimestamp())).addOnSuccessListener { adminStatus = "Tracking interval updated" }.addOnFailureListener { adminStatus = "Config failed: ${it.message}" } }
}

@Composable private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); content() } } }
@Composable private fun StatusPill(text: String, active: Boolean) { AssistChip(onClick = {}, label = { Text(text) }, colors = AssistChipDefaults.assistChipColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) }

@Composable
private fun TrackerScreen(status: String, location: String, deviceId: String, tracking: Boolean, alarm: String, listener: String, lastCommand: String, commandResult: String, onGrant: () -> Unit, onFetch: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onRing: () -> Unit, onStopAlarm: () -> Unit, onAdmin: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Location Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Secure device monitoring", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = onAdmin) { Text("Admin") } } }
        item { SectionCard("Device status") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (tracking) "Tracking active" else "Tracking paused", style = MaterialTheme.typography.titleLarge); Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }; StatusPill(if (tracking) "ACTIVE" else "OFFLINE", tracking) }; HorizontalDivider(); Text("Device ID", style = MaterialTheme.typography.labelMedium); Text(deviceId, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        item { SectionCard("Current location", "Fetch your device's latest available location") { Text(location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) { Text("Locate Now") }; TextButton(onClick = onGrant, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Location permission") } } }
        item { SectionCard("Background tracking") { Text(if (tracking) "Automatic background updates are running." else "Enable tracking to send periodic location updates.", color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = if (tracking) onStop else onStart, modifier = Modifier.fillMaxWidth()) { Text(if (tracking) "Stop Tracking" else "Start Tracking") } } }
        item { SectionCard("Remote commands") { Text("Listener: $listener", style = MaterialTheme.typography.bodyMedium); Text("Last command: $lastCommand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Result: $commandResult", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { SectionCard("Alarm") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Status: $alarm"); StatusPill(alarm, alarm == "RINGING") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onRing, modifier = Modifier.weight(1f)) { Text("Test Ring") }; OutlinedButton(onClick = onStopAlarm, modifier = Modifier.weight(1f)) { Text("Stop") } } } }
        item { OutlinedButton(onClick = onAdmin, modifier = Modifier.fillMaxWidth()) { Text("Manage Devices") } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AdminScreen(devices: List<AdminDevice>, status: String, onRefresh: () -> Unit, onFetch: (String) -> Unit, onRing: (String) -> Unit, onStop: (String) -> Unit, onInterval: (Pair<String, Int>) -> Unit, onMap: (AdminDevice) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    var interval by remember { mutableStateOf("15") }; var deviceToRemove by remember { mutableStateOf<AdminDevice?>(null) }
    if (deviceToRemove != null) AlertDialog(onDismissRequest = { deviceToRemove = null }, title = { Text("Remove device?") }, text = { Text("Remove ${deviceToRemove!!.name} from your device list? This action cannot be undone.") }, confirmButton = { Button(onClick = { onRemove(deviceToRemove!!.id); deviceToRemove = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deviceToRemove = null }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("Device Management", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Manage and control your connected devices", color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = onBack) { Text("Tracker") } } }
        item { SectionCard("Your devices", status) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { StatusPill("${devices.size} DEVICE(S)", devices.isNotEmpty()); Button(onClick = onRefresh) { Text("Refresh") } }; OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit) }, label = { Text("Tracking interval (minutes)") }, supportingText = { Text("Minimum 15 minutes") }, modifier = Modifier.fillMaxWidth()) } }
        if (devices.isEmpty()) item { SectionCard("No devices found", "Tap Refresh to load registered devices.") {} }
        items(devices) { d -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(d.model.ifBlank { "Android device" }, color = MaterialTheme.colorScheme.onSurfaceVariant) }; StatusPill(if (d.latitude != null) "LOCATION READY" else "WAITING", d.latitude != null) }; HorizontalDivider(); Text("Battery: ${d.battery}"); Text("Location: ${d.location}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { onFetch(d.id) }, modifier = Modifier.weight(1f)) { Text("Locate") }; if (d.latitude != null && d.longitude != null) OutlinedButton(onClick = { onMap(d) }, modifier = Modifier.weight(1f)) { Text("View Map") } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { onRing(d.id) }, modifier = Modifier.weight(1f)) { Text("Ring") }; OutlinedButton(onClick = { onStop(d.id) }, modifier = Modifier.weight(1f)) { Text("Stop Ring") } }; Button(onClick = { onInterval(d.id to (interval.toIntOrNull() ?: 15)) }, modifier = Modifier.fillMaxWidth()) { Text("Apply Tracking Interval") }; TextButton(onClick = { deviceToRemove = d }, modifier = Modifier.align(Alignment.End)) { Text("Remove Device", color = MaterialTheme.colorScheme.error) } } } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
