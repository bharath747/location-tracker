package com.bharath.locationtracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Connecting to Firebase...")
    private var locationText by mutableStateOf("No location fetched yet")
    private var deviceId by mutableStateOf("")
    private var trackingActive by mutableStateOf(false)
    private var alarmStatus by mutableStateOf("IDLE")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { p ->
        val granted = p[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            p[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            status = "Location permission granted. Fetching location..."
            fetchAndUploadLocation()
        } else status = "Location permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = getTrackerDeviceId()
        signInAndRegister()
        setContent {
            MaterialTheme {
                TrackerScreen(
                    status, locationText, deviceId, trackingActive, alarmStatus,
                    { requestLocationPermission() },
                    { fetchAndUploadLocation() },
                    { startTracking() },
                    { stopTracking() },
                    { testRing() },
                    { stopAlarm() }
                )
            }
        }
    }

    private fun getTrackerDeviceId(): String {
        val prefs = getSharedPreferences("tracker", Context.MODE_PRIVATE)
        return prefs.getString("deviceId", null)
            ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                .also { prefs.edit().putString("deviceId", it).apply() }
    }

    private fun signInAndRegister() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) registerDevice()
        else auth.signInAnonymously().addOnSuccessListener { registerDevice() }
            .addOnFailureListener { e -> status = "Firebase login failed: ${e.message}" }
    }

    private fun registerDevice() {
        val data = hashMapOf<String, Any?>(
            "deviceId" to deviceId,
            "firebaseUid" to FirebaseAuth.getInstance().currentUser?.uid,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE,
            "lastSeen" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance().collection("devices").document(deviceId).set(data)
            .addOnSuccessListener { status = "Device registered with Firebase" }
            .addOnFailureListener { e -> status = "Registration failed: ${e.message}" }
    }

    private fun startTracking() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) { status = "Grant location permission before starting tracking"; return }
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        trackingActive = true
        status = "Background tracking started"
    }

    private fun stopTracking() {
        stopService(Intent(this, TrackingService::class.java))
        trackingActive = false
        status = "Background tracking stopped"
    }

    private fun testRing() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java))
            alarmStatus = "RINGING"
            status = "Test alarm started"
        } catch (e: Exception) {
            alarmStatus = "ERROR"
            status = "Unable to start alarm: ${e.message}"
        }
    }

    private fun stopAlarm() {
        try {
            stopService(Intent(this, AlarmService::class.java))
            alarmStatus = "IDLE"
            status = "Alarm stopped"
        } catch (e: Exception) {
            status = "Unable to stop alarm: ${e.message}"
        }
    }

    private fun requestLocationPermission() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun fetchAndUploadLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status = "Please grant location permission first"
            return
        }
        status = "Fetching location..."
        lifecycleScope.launch {
            try {
                locationText = LocationRepository.fetchAndUpload(this@MainActivity)
                status = "Location uploaded to Firebase"
            } catch (e: Exception) {
                status = "Location error: ${e.message}"
            }
        }
    }
}

@Composable
private fun TrackerScreen(
    status: String,
    location: String,
    deviceId: String,
    trackingActive: Boolean,
    alarmStatus: String,
    onGrant: () -> Unit,
    onFetch: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestRing: () -> Unit,
    onStopAlarm: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Location Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("Status: $status")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Device ID", style = MaterialTheme.typography.titleSmall)
                Text(deviceId)
                Spacer(Modifier.height(12.dp))
                Text("Current Location", style = MaterialTheme.typography.titleMedium)
                Text(location)
            }
        }
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Grant Location Permission") }
        Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) { Text("Fetch & Upload Location") }

        Text("Background Tracking: " + if (trackingActive) "ACTIVE" else "STOPPED")
        if (!trackingActive) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start Background Tracking") }
        } else {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop Background Tracking") }
        }

        HorizontalDivider()
        Text("Remote Alarm", style = MaterialTheme.typography.titleLarge)
        Text("Alarm Status: $alarmStatus")
        Button(onClick = onTestRing, modifier = Modifier.fillMaxWidth()) { Text("🔔 Test Ring") }
        OutlinedButton(onClick = onStopAlarm, modifier = Modifier.fillMaxWidth()) { Text("Stop Alarm") }
    }
}
