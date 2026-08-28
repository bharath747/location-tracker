package com.bharath.locationtracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Connecting to Firebase...")
    private var locationText by mutableStateOf("No location fetched yet")
    private var deviceId by mutableStateOf("")
    private var trackingActive by mutableStateOf(false)
    private var alarmStatus by mutableStateOf("IDLE")
    private var adminMode by mutableStateOf(false)
    private var adminDevices by mutableStateOf<List<AdminDevice>>(emptyList())
    private var adminStatus by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) fetchAndUploadLocation() else status = "Location permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = LocationRepository.getDeviceId(this)
        trackingActive = prefs().getBoolean("trackingEnabled", false)
        if (deviceId.isNotBlank()) signInAndRegister()
        if (trackingActive && deviceId.isNotBlank()) restoreTrackingIfPossible()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface {
                    when {
                        deviceId.isBlank() -> RegistrationScreen(
                            onRegister = ::registerName,
                            status = status
                        )
                        adminMode -> AdminScreen(
                            devices = adminDevices,
                            status = adminStatus,
                            onRefresh = ::loadDevices,
                            onFetch = { sendCommand(it, "FETCH_LOCATION") },
                            onRing = { sendCommand(it, "RING") },
                            onStop = { sendCommand(it, "STOP_RING") },
                            onInterval = { updateInterval(it.first, it.second) },
                            onMap = ::openGoogleMaps,
                            onRemove = ::removeDevice,
                            onBack = { adminMode = false }
                        )
                        else -> TrackerScreen(
                            status, locationText, deviceId, trackingActive, alarmStatus,
                            TrackerRuntimeStatus.commandListenerStatus,
                            TrackerRuntimeStatus.lastCommand,
                            TrackerRuntimeStatus.lastCommandResult,
                            ::requestLocationPermission, ::fetchAndUploadLocation,
                            ::startTracking, ::stopTracking, ::testRing, ::stopAlarm,
                            { adminMode = true; loadDevices() }
                        )
                    }
                }
            }
        }
    }

    private fun prefs() = getSharedPreferences("tracker", Context.MODE_PRIVATE)

    private fun registerName(name: String) {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 2) { status = "Enter a valid device name"; return }
        deviceId = clean
        prefs().edit().putString("deviceId", clean).apply()
        status = "Registering device..."
        signInAndRegister()
    }

    private fun signInAndRegister() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) registerDevice()
        else auth.signInAnonymously().addOnSuccessListener { registerDevice() }
            .addOnFailureListener { status = "Firebase login failed: ${it.message}" }
    }

    private fun registerDevice() {
        val data = hashMapOf<String, Any?>(
            "deviceId" to deviceId,
            "firebaseUid" to FirebaseAuth.getInstance().currentUser?.uid,
            "deviceName" to deviceId,
            "lastSeen" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance().collection("devices").document(deviceId).set(data)
            .addOnSuccessListener { status = "Device connected and ready" }
            .addOnFailureListener { status = "Registration failed: ${it.message}" }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun restoreTrackingIfPossible() {
        if (!hasLocationPermission()) {
            trackingActive = false
            prefs().edit().putBoolean("trackingEnabled", false).apply()
            status = "Tracking paused: location permission required"
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
            status = "Restoring background tracking..."
        } catch (e: Exception) { trackingActive = false; status = "Could not restore tracking: ${e.message}" }
    }

    private fun startTracking() {
        if (!hasLocationPermission()) { status = "Grant location permission first"; return }
        prefs().edit().putBoolean("trackingEnabled", true).apply()
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        trackingActive = true
        status = "Background tracking is active"
    }

    private fun stopTracking() {
        prefs().edit().putBoolean("trackingEnabled", false).apply()
        stopService(Intent(this, TrackingService::class.java))
        trackingActive = false
        status = "Background tracking stopped"
    }

    private fun testRing() = try {
        ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java))
        alarmStatus = "RINGING"; status = "Alarm started"
    } catch (e: Exception) { status = "Alarm error: ${e.message}" }

    private fun stopAlarm() { stopService(Intent(this, AlarmService::class.java)); alarmStatus = "IDLE"; status = "Alarm stopped" }
    private fun requestLocationPermission() = permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    private fun fetchAndUploadLocation() {
        if (!hasLocationPermission()) { status = "Please grant location permission"; return }
        status = "Fetching current location..."
        lifecycleScope.launch {
            try { locationText = LocationRepository.fetchAndUpload(this@MainActivity); status = "Location updated successfully" }
            catch (e: Exception) { status = "Location error: ${e.message}" }
        }
    }

    private fun loadDevices() {
        adminStatus = "Loading devices..."
        FirebaseFirestore.getInstance().collection("devices").get().addOnSuccessListener { query ->
            adminDevices = query.documents.map { d ->
                val lat = (d.get("latitude") as? Number)?.toDouble()
                val lon = (d.get("longitude") as? Number)?.toDouble()
                AdminDevice(d.id, d.getString("deviceName") ?: d.id, d.get("batteryLevel")?.toString() ?: "Unknown", lat, lon)
            }
            adminStatus = "${adminDevices.size} device(s) available"
        }.addOnFailureListener { adminStatus = "Load failed: ${it.message}" }
    }

    private fun openGoogleMaps(device: AdminDevice) {
        val lat = device.latitude ?: run { adminStatus = "No location available"; return }
        val lon = device.longitude ?: run { adminStatus = "No location available"; return }
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
    }

    private fun removeDevice(id: String) {
        adminStatus = "Removing device..."
        FirebaseFirestore.getInstance().collection("devices").document(id).delete()
            .addOnSuccessListener { adminDevices = adminDevices.filterNot { it.id == id }; adminStatus = "Device removed" }
            .addOnFailureListener { adminStatus = "Remove failed: ${it.message}" }
    }

    private fun sendCommand(id: String, action: String) {
        adminStatus = "Sending command..."
        FirebaseFirestore.getInstance().collection("devices").document(id).collection("commands").add(
            mapOf("action" to action, "status" to "PENDING", "createdAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener { adminStatus = "$action command sent" }
            .addOnFailureListener { adminStatus = "Command failed: ${it.message}" }
    }

    private fun updateInterval(id: String, minutes: Int) {
        FirebaseFirestore.getInstance().collection("devices").document(id).collection("config").document("tracking")
            .set(mapOf("intervalMinutes" to minutes.coerceAtLeast(15), "updatedAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener { adminStatus = "Tracking interval updated" }
            .addOnFailureListener { adminStatus = "Config failed: ${it.message}" }
    }
}
