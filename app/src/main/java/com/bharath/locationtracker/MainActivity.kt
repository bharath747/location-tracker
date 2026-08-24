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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Connecting to Firebase...")
    private var locationText by mutableStateOf("No location fetched yet")
    private var deviceId by mutableStateOf("")
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        status = if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true || p[Manifest.permission.ACCESS_COARSE_LOCATION] == true) "Location permission granted" else "Location permission denied"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); deviceId = getDeviceId(); signInAndRegister()
        setContent { MaterialTheme { TrackerScreen(status, locationText, deviceId, { requestLocationPermission() }, { fetchAndUploadLocation() }) } }
    }
    private fun getDeviceId(): String {
        val prefs = getSharedPreferences("tracker", Context.MODE_PRIVATE)
        return prefs.getString("deviceId", null) ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).also { prefs.edit().putString("deviceId", it).apply() }
    }
    private fun signInAndRegister() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) registerDevice() else auth.signInAnonymously().addOnSuccessListener { registerDevice() }.addOnFailureListener { e -> status = "Firebase login failed: ${e.message}" }
    }
    private fun registerDevice() {
        val data = hashMapOf<String, Any?>("deviceId" to deviceId, "firebaseUid" to FirebaseAuth.getInstance().currentUser?.uid, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}", "androidVersion" to Build.VERSION.RELEASE, "lastSeen" to FieldValue.serverTimestamp())
        FirebaseFirestore.getInstance().collection("devices").document(deviceId).set(data).addOnSuccessListener { status = "Device registered with Firebase" }.addOnFailureListener { e -> status = "Registration failed: ${e.message}" }
    }
    private fun requestLocationPermission() { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    private fun fetchAndUploadLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { status = "Please grant location permission first"; return }
        status = "Fetching location..."
        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            if (loc == null) { status = "Location unavailable. Turn on GPS and try again."; return@addOnSuccessListener }
            locationText = String.format(Locale.US, "Latitude: %.6f\nLongitude: %.6f\nAccuracy: %.1f m", loc.latitude, loc.longitude, loc.accuracy)
            uploadLocation(loc.latitude, loc.longitude, loc.accuracy)
        }.addOnFailureListener { e -> status = "Location error: ${e.message}" }
    }
    private fun uploadLocation(lat: Double, lng: Double, accuracy: Float) {
        val (level, charging) = batteryInfo()
        val data = hashMapOf<String, Any?>("latitude" to lat, "longitude" to lng, "accuracy" to accuracy, "batteryLevel" to level, "isCharging" to charging, "lastSeen" to FieldValue.serverTimestamp())
        val doc = FirebaseFirestore.getInstance().collection("devices").document(deviceId)
        doc.update(data).addOnSuccessListener {
            doc.collection("locationHistory").add(data + mapOf("recordedAt" to FieldValue.serverTimestamp()))
            status = "Location uploaded to Firebase"
        }.addOnFailureListener { e -> status = "Upload failed: ${e.message}" }
    }
    private fun batteryInfo(): Pair<Int, Boolean> {
        val level = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return level to ((intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }
}
@Composable private fun TrackerScreen(status: String, location: String, deviceId: String, onGrant: () -> Unit, onFetch: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Location Tracker", style = MaterialTheme.typography.headlineMedium); Text("Status: $status")
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Device ID", style = MaterialTheme.typography.titleSmall); Text(deviceId); Spacer(Modifier.height(12.dp)); Text("Current Location", style = MaterialTheme.typography.titleMedium); Text(location) } }
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Grant Location Permission") }
        Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) { Text("Fetch & Upload Location") }
    }
}