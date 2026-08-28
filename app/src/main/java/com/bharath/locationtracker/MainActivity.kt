package com.bharath.locationtracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Connecting to Firebase...")
    private var locationText by mutableStateOf("No location fetched yet")
    private var deviceId by mutableStateOf("")
    private var trackingActive by mutableStateOf(false)
    private var alarmStatus by mutableStateOf("IDLE")
    private var adminMode by mutableStateOf(false)
    private var showAdminPinDialog by mutableStateOf(false)
    private var adminDevices by mutableStateOf<List<AdminDevice>>(emptyList())
    private var adminStatus by mutableStateOf("")
    private val adminPin = "12@#34£_56&-"
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions -> if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) fetchAndUploadLocation() else status = "Location permission denied" }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); deviceId = LocationRepository.getDeviceId(this); trackingActive = prefs().getBoolean("trackingEnabled", false); if (deviceId.isNotBlank()) signInAndRegister(); if (trackingActive && deviceId.isNotBlank()) restoreTrackingIfPossible(); setContent { MaterialTheme(colorScheme = lightColorScheme()) { Surface { when { deviceId.isBlank() -> RegistrationScreen(::registerName, status); adminMode -> AdminScreen(adminDevices, adminStatus, ::loadDevices, { sendCommand(it, "FETCH_LOCATION") }, { sendCommand(it, "FETCH_DEVICE_STATUS") }, { sendCommand(it, "RING") }, { sendCommand(it, "STOP_RING") }, { updateInterval(it.first, it.second) }, ::openGoogleMaps, ::removeDevice, { adminMode = false }); else -> TrackerScreen(status, locationText, deviceId, trackingActive, alarmStatus, TrackerRuntimeStatus.commandListenerStatus, TrackerRuntimeStatus.lastCommand, TrackerRuntimeStatus.lastCommandResult, ::requestLocationPermission, ::fetchAndUploadLocation, ::startTracking, ::stopTracking, ::testRing, ::stopAlarm, { showAdminPinDialog = true }) }; if (showAdminPinDialog) AdminPinDialog({ showAdminPinDialog = false }) { showAdminPinDialog = false; adminMode = true; loadDevices() } } } } }

    @Composable private fun AdminPinDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) { var pin by remember { mutableStateOf("") }; var error by remember { mutableStateOf(false) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Admin Access") }, text = { OutlinedTextField(pin, { pin = it; error = false }, label = { Text("Admin PIN") }, isError = error, supportingText = { if (error) Text("Incorrect PIN") }) }, confirmButton = { Button({ if (pin == adminPin) onSuccess() else error = true }) { Text("Unlock") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } }) }
    private fun prefs() = getSharedPreferences("tracker", Context.MODE_PRIVATE)
    private fun registerName(name: String) { val clean = name.trim().replace(Regex("\\s+"), " "); if (clean.length < 2) { status = "Enter a valid device name"; return }; deviceId = clean; prefs().edit().putString("deviceId", clean).apply(); status = "Registering device..."; signInAndRegister() }
    private fun signInAndRegister() { val auth = FirebaseAuth.getInstance(); if (auth.currentUser != null) registerDevice() else auth.signInAnonymously().addOnSuccessListener { registerDevice() }.addOnFailureListener { status = "Firebase login failed: ${it.message}" } }
    private fun registerDevice() { val data = hashMapOf<String, Any?>("deviceId" to deviceId, "firebaseUid" to FirebaseAuth.getInstance().currentUser?.uid, "deviceName" to deviceId, "lastSeen" to FieldValue.serverTimestamp()); FirebaseFirestore.getInstance().collection("devices").document(deviceId).set(data).addOnSuccessListener { status = "Device connected and ready" }.addOnFailureListener { status = "Registration failed: ${it.message}" } }
    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun restoreTrackingIfPossible() { if (!hasLocationPermission()) { trackingActive = false; prefs().edit().putBoolean("trackingEnabled", false).apply(); status = "Tracking paused: location permission required"; return }; try { ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java)); status = "Restoring background tracking..." } catch (e: Exception) { trackingActive = false; status = "Could not restore tracking: ${e.message}" } }
    private fun startTracking() { if (!hasLocationPermission()) { status = "Grant location permission first"; return }; prefs().edit().putBoolean("trackingEnabled", true).apply(); ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java)); trackingActive = true; status = "Background tracking is active" }
    private fun stopTracking() { prefs().edit().putBoolean("trackingEnabled", false).apply(); stopService(Intent(this, TrackingService::class.java)); trackingActive = false; status = "Background tracking stopped" }
    private fun testRing() = try { ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java)); alarmStatus = "RINGING"; status = "Alarm started" } catch (e: Exception) { status = "Alarm error: ${e.message}" }
    private fun stopAlarm() { stopService(Intent(this, AlarmService::class.java)); alarmStatus = "IDLE"; status = "Alarm stopped" }
    private fun requestLocationPermission() = permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    private fun fetchAndUploadLocation() { if (!hasLocationPermission()) { status = "Please grant location permission"; return }; status = "Fetching current location..."; lifecycleScope.launch { try { locationText = LocationRepository.fetchAndUpload(this@MainActivity); status = "Location updated successfully" } catch (e: Exception) { status = "Location error: ${e.message}" } } }
    private fun formatTimestamp(value: Any?): String = when (value) { is Timestamp -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(value.toDate()); is Date -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(value); else -> "Not available" }
    private fun bytes(value: Any?): String { val n = (value as? Number)?.toLong() ?: return "Not available"; val mb = 1024L * 1024L; val gb = mb * 1024L; return if (n >= gb) String.format("%.2f GB", n.toDouble() / gb) else String.format("%.1f MB", n.toDouble() / mb) }
    private fun yesNo(value: Any?): String = when (value as? Boolean) { true -> "Yes"; false -> "No"; null -> "Not available" }
    private fun loadDevices() { adminStatus = "Loading devices..."; FirebaseFirestore.getInstance().collection("devices").get().addOnSuccessListener { query -> adminDevices = query.documents.map { d -> val lat = (d.get("latitude") as? Number)?.toDouble(); val lon = (d.get("longitude") as? Number)?.toDouble(); val details = listOf("Manufacturer" to (d.getString("manufacturer") ?: "Not available"), "Brand" to (d.getString("brand") ?: "Not available"), "Model" to (d.getString("model") ?: "Not available"), "Android" to ((d.getString("androidVersion") ?: "Not available") + " (SDK " + (d.get("sdkInt")?.toString() ?: "-") + ")"), "Battery" to (d.get("batteryLevel")?.toString() ?: "Unknown"), "Charging" to yesNo(d.get("isCharging")), "Power saver" to yesNo(d.get("powerSaveMode")), "Network" to (d.getString("networkType") ?: "Not available"), "Network connected" to yesNo(d.get("networkConnected")), "Location enabled" to yesNo(d.get("locationEnabled")), "Fine location permission" to yesNo(d.get("fineLocationPermission")), "Coarse location permission" to yesNo(d.get("coarseLocationPermission")), "Notifications allowed" to yesNo(d.get("notificationPermission")), "Tracking active" to yesNo(d.get("trackingServiceActive")), "Storage total" to bytes(d.get("storageTotalBytes")), "Storage free" to bytes(d.get("storageFreeBytes")), "Storage used" to bytes(d.get("storageUsedBytes"))); AdminDevice(d.id, d.getString("deviceName") ?: d.id, d.get("batteryLevel")?.toString() ?: "Unknown", lat, lon, formatTimestamp(d.get("locationFetchedAt")), formatTimestamp(d.get("statusFetchedAt")), details) }; adminStatus = "${adminDevices.size} device(s) available" }.addOnFailureListener { adminStatus = "Load failed: ${it.message}" } }
    private fun openGoogleMaps(device: AdminDevice) { val lat = device.latitude ?: run { adminStatus = "No location available"; return }; val lon = device.longitude ?: run { adminStatus = "No location available"; return }; startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }
    private fun removeDevice(id: String) { adminStatus = "Removing device..."; FirebaseFirestore.getInstance().collection("devices").document(id).delete().addOnSuccessListener { adminDevices = adminDevices.filterNot { it.id == id }; adminStatus = "Device removed" }.addOnFailureListener { adminStatus = "Remove failed: ${it.message}" } }
    private fun sendCommand(id: String, action: String) { adminStatus = "Sending command..."; FirebaseFirestore.getInstance().collection("devices").document(id).collection("commands").add(mapOf("action" to action, "status" to "PENDING", "createdAt" to FieldValue.serverTimestamp())).addOnSuccessListener { adminStatus = "$action command sent. Refresh after the device executes it." }.addOnFailureListener { adminStatus = "Command failed: ${it.message}" } }
    private fun updateInterval(id: String, minutes: Int) { FirebaseFirestore.getInstance().collection("devices").document(id).collection("config").document("tracking").set(mapOf("intervalMinutes" to minutes.coerceAtLeast(15), "updatedAt" to FieldValue.serverTimestamp())).addOnSuccessListener { adminStatus = "Tracking interval updated" }.addOnFailureListener { adminStatus = "Config failed: ${it.message}" } }
}
