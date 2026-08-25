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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

data class AdminDevice(val id: String, val name: String, val model: String, val battery: String, val location: String)

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
        signInAndRegister()
        setContent { MaterialTheme { if (adminMode) AdminScreen(adminDevices, adminStatus, { loadDevices() }, { sendCommand(it, "FETCH_LOCATION") }, { sendCommand(it, "RING") }, { sendCommand(it, "STOP_RING") }, { updateInterval(it.first, it.second) }, { adminMode = false }) else TrackerScreen(status, locationText, deviceId, trackingActive, alarmStatus, { requestLocationPermission() }, { fetchAndUploadLocation() }, { startTracking() }, { stopTracking() }, { testRing() }, { stopAlarm() }, { adminMode = true; loadDevices() }) } }
    }

    private fun getTrackerDeviceId(): String { val p=getSharedPreferences("tracker",Context.MODE_PRIVATE); return p.getString("deviceId",null) ?: Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID).also{p.edit().putString("deviceId",it).apply()} }
    private fun signInAndRegister() { val a=FirebaseAuth.getInstance(); if(a.currentUser!=null) registerDevice() else a.signInAnonymously().addOnSuccessListener{registerDevice()}.addOnFailureListener{status="Firebase login failed: ${it.message}"} }
    private fun registerDevice() { val d=hashMapOf<String,Any?>("deviceId" to deviceId,"firebaseUid" to FirebaseAuth.getInstance().currentUser?.uid,"manufacturer" to Build.MANUFACTURER,"model" to Build.MODEL,"deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}","androidVersion" to Build.VERSION.RELEASE,"lastSeen" to FieldValue.serverTimestamp()); FirebaseFirestore.getInstance().collection("devices").document(deviceId).set(d).addOnSuccessListener{status="Device registered with Firebase"}.addOnFailureListener{status="Registration failed: ${it.message}"} }
    private fun startTracking(){val g=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;if(!g){status="Grant location permission first";return};ContextCompat.startForegroundService(this,Intent(this,TrackingService::class.java));trackingActive=true;status="Background tracking started"}
    private fun stopTracking(){stopService(Intent(this,TrackingService::class.java));trackingActive=false;status="Background tracking stopped"}
    private fun testRing(){try{ContextCompat.startForegroundService(this,Intent(this,AlarmService::class.java));alarmStatus="RINGING";status="Test alarm started"}catch(e:Exception){status="Alarm error: ${e.message}"}}
    private fun stopAlarm(){stopService(Intent(this,AlarmService::class.java));alarmStatus="IDLE";status="Alarm stopped"}
    private fun requestLocationPermission(){permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
    private fun fetchAndUploadLocation(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){status="Please grant location permission";return};status="Fetching location...";lifecycleScope.launch{try{locationText=LocationRepository.fetchAndUpload(this@MainActivity);status="Location uploaded to Firebase"}catch(e:Exception){status="Location error: ${e.message}"}}}

    private fun loadDevices(){adminStatus="Loading devices...";FirebaseFirestore.getInstance().collection("devices").get().addOnSuccessListener{q->adminDevices=q.documents.map{d->AdminDevice(d.id,d.getString("deviceName")?:d.id,d.getString("model")?:"",d.get("batteryLevel")?.toString()?:"Unknown",listOfNotNull(d.get("latitude"),d.get("longitude")).joinToString(", ").ifBlank{"No location"})};adminStatus="${adminDevices.size} device(s) loaded"}.addOnFailureListener{adminStatus="Load failed: ${it.message}"}}
    private fun sendCommand(id:String,action:String){adminStatus="Sending $action...";FirebaseFirestore.getInstance().collection("devices").document(id).collection("commands").add(mapOf("action" to action,"status" to "PENDING","createdAt" to FieldValue.serverTimestamp())).addOnSuccessListener{adminStatus="$action command created for $id"}.addOnFailureListener{adminStatus="Command failed: ${it.message}"}}
    private fun updateInterval(id:String,minutes:Int){FirebaseFirestore.getInstance().collection("devices").document(id).collection("config").document("tracking").set(mapOf("intervalMinutes" to minutes,"updatedAt" to FieldValue.serverTimestamp())).addOnSuccessListener{adminStatus="Interval updated to $minutes minutes"}.addOnFailureListener{adminStatus="Config failed: ${it.message}"}}
}

@Composable private fun TrackerScreen(status:String,location:String,deviceId:String,tracking:Boolean,alarm:String,onGrant:()->Unit,onFetch:()->Unit,onStart:()->Unit,onStop:()->Unit,onRing:()->Unit,onStopAlarm:()->Unit,onAdmin:()->Unit){LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Location Tracker",style=MaterialTheme.typography.headlineMedium);Text("Status: $status");Text("Device ID: $deviceId");Text(location);Button(onClick=onGrant,modifier=Modifier.fillMaxWidth()){Text("Grant Location Permission")};Button(onClick=onFetch,modifier=Modifier.fillMaxWidth()){Text("Fetch & Upload Location")};Text("Background Tracking: ${if(tracking)"ACTIVE" else "STOPPED"}");Button(onClick=if(tracking)onStop else onStart,modifier=Modifier.fillMaxWidth()){Text(if(tracking)"Stop Background Tracking" else "Start Background Tracking")};HorizontalDivider();Text("Remote Alarm",style=MaterialTheme.typography.titleLarge);Text("Alarm Status: $alarm");Button(onClick=onRing,modifier=Modifier.fillMaxWidth()){Text("Test Ring")};OutlinedButton(onClick=onStopAlarm,modifier=Modifier.fillMaxWidth()){Text("Stop Alarm")};HorizontalDivider();Button(onClick=onAdmin,modifier=Modifier.fillMaxWidth()){Text("Open Admin Screen")}}}}

@Composable private fun AdminScreen(devices:List<AdminDevice>,status:String,onRefresh:()->Unit,onFetch:(String)->Unit,onRing:(String)->Unit,onStop:(String)->Unit,onInterval:(Pair<String,Int>)->Unit,onBack:()->Unit){var interval by remember{mutableStateOf("5")};LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Location Tracker Admin",style=MaterialTheme.typography.headlineSmall);TextButton(onClick=onBack){Text("Tracker")}};Text(status);Button(onClick=onRefresh,modifier=Modifier.fillMaxWidth()){Text("Refresh Devices")};OutlinedTextField(value=interval,onValueChange={interval=it.filter(Char::isDigit)},label={Text("Tracking interval minutes")},modifier=Modifier.fillMaxWidth())};items(devices){d->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(d.name,style=MaterialTheme.typography.titleMedium);Text("ID: ${d.id}");Text("Model: ${d.model}");Text("Battery: ${d.battery}");Text("Location: ${d.location}");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={onFetch(d.id)}){Text("Fetch")};Button(onClick={onRing(d.id)}){Text("Ring")}};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={onStop(d.id)}){Text("Stop Ring")};OutlinedButton(onClick={onInterval(d.id to (interval.toIntOrNull()?:5))}){Text("Set Interval")}}}}}}}
