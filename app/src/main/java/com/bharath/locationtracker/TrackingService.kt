package com.bharath.locationtracker

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object TrackerRuntimeStatus {
    var commandListenerStatus by mutableStateOf("STOPPED")
    var lastCommand by mutableStateOf("None")
    var lastCommandResult by mutableStateOf("None")
}

class TrackingService : Service() {
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null
    private var commandListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putBoolean("trackingEnabled", true).apply()
        createChannel()
        startForeground(1001, NotificationCompat.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Location tracking active").setContentText("Hybrid command delivery active").setOngoing(true).build())
        TrackerRuntimeStatus.commandListenerStatus = "CONNECTING"
        scheduleFromFirebase(); listenForCommands()
        return START_STICKY
    }

    private fun scheduleFromFirebase() {
        val id = LocationRepository.getDeviceId(this); listener?.remove()
        listener = FirebaseFirestore.getInstance().collection("devices").document(id).collection("config").document("tracking").addSnapshotListener { snap, error ->
            if (error != null) { TrackerRuntimeStatus.commandListenerStatus = "CONFIG ERROR: ${error.message ?: "unknown"}"; return@addSnapshotListener }
            val enabled = snap?.getBoolean("trackingEnabled") ?: true
            val minutes = (snap?.getLong("intervalMinutes") ?: 15L).coerceAtLeast(15L)
            if (enabled) schedule(minutes) else WorkManager.getInstance(this).cancelUniqueWork("location-tracking")
        }
    }

    private fun listenForCommands() {
        val ref = FirebaseFirestore.getInstance().collection("devices").document(LocationRepository.getDeviceId(this)); commandListener?.remove(); TrackerRuntimeStatus.commandListenerStatus = "CONNECTING"
        commandListener = ref.collection("commands").whereEqualTo("status", "PENDING").addSnapshotListener { snapshots, error ->
            if (error != null) { TrackerRuntimeStatus.commandListenerStatus = "ERROR: ${error.message ?: "unknown"}"; return@addSnapshotListener }
            TrackerRuntimeStatus.commandListenerStatus = "ACTIVE"
            snapshots?.documents?.forEach { command ->
                val action = command.getString("action")?.uppercase() ?: return@forEach
                TrackerRuntimeStatus.lastCommand = action; TrackerRuntimeStatus.lastCommandResult = "RECEIVED"; executeCommand(command.id, action)
            }
        }
    }

    private fun executeCommand(commandId: String, action: String) {
        val commandRef = FirebaseFirestore.getInstance().collection("devices").document(LocationRepository.getDeviceId(this)).collection("commands").document(commandId)
        commandRef.update(mapOf("status" to "PROCESSING", "receivedAt" to FieldValue.serverTimestamp()))
        TrackerRuntimeStatus.lastCommandResult = "PROCESSING"
        when (action) {
            "RING" -> { ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java)); complete(commandRef, true) }
            "STOP_RING" -> { stopService(Intent(this, AlarmService::class.java)); complete(commandRef, true) }
            "FETCH_LOCATION" -> CoroutineScope(Dispatchers.IO).launch {
                runCatching { LocationRepository.fetchAndUpload(this@TrackingService) }.onSuccess { complete(commandRef, true) }.onFailure { fail(commandRef, it.message ?: "Location fetch failed") }
            }
            "FETCH_DEVICE_STATUS" -> CoroutineScope(Dispatchers.IO).launch {
                runCatching { uploadDeviceStatus() }.onSuccess { complete(commandRef, true) }.onFailure { fail(commandRef, it.message ?: "Status fetch failed") }
            }
            else -> { commandRef.update("status", "IGNORED", "executedAt", FieldValue.serverTimestamp()); TrackerRuntimeStatus.lastCommandResult = "IGNORED" }
        }
    }

    private fun complete(ref: com.google.firebase.firestore.DocumentReference, ok: Boolean) { ref.update("status", if (ok) "EXECUTED" else "FAILED", "executedAt", FieldValue.serverTimestamp()); TrackerRuntimeStatus.lastCommandResult = if (ok) "EXECUTED" else "FAILED" }
    private fun fail(ref: com.google.firebase.firestore.DocumentReference, message: String) { ref.update("status", "FAILED", "error", message); TrackerRuntimeStatus.lastCommandResult = "FAILED: $message" }

    private fun uploadDeviceStatus() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged != 0 || battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        val powerSave = getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled ?: false else Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifications = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val deviceRef = FirebaseFirestore.getInstance().collection("devices").document(LocationRepository.getDeviceId(this))
        deviceRef.update(mapOf(
            "batteryLevel" to if (percent >= 0) "$percent%" else "Unknown",
            "batteryPercent" to percent,
            "isCharging" to charging,
            "powerSaveMode" to powerSave,
            "networkType" to network,
            "networkConnected" to (caps != null),
            "locationEnabled" to locationEnabled,
            "fineLocationPermission" to fine,
            "coarseLocationPermission" to coarse,
            "notificationPermission" to notifications,
            "trackingServiceActive" to true,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "deviceName" to LocationRepository.getDeviceId(this),
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "storageTotalBytes" to total,
            "storageFreeBytes" to free,
            "storageUsedBytes" to (total - free),
            "statusFetchedAt" to FieldValue.serverTimestamp(),
            "lastSeen" to FieldValue.serverTimestamp()
        )).get()
    }

    private fun schedule(minutes: Long) { val request = PeriodicWorkRequestBuilder<LocationWorker>(minutes, TimeUnit.MINUTES).build(); WorkManager.getInstance(this).enqueueUniquePeriodicWork("location-tracking", ExistingPeriodicWorkPolicy.UPDATE, request) }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tracking", "Location Tracking", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { listener?.remove(); commandListener?.remove(); TrackerRuntimeStatus.commandListenerStatus = "STOPPED"; WorkManager.getInstance(this).cancelUniqueWork("location-tracking"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

class LocationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext Result.failure()
        try { LocationRepository.fetchAndUpload(applicationContext); Result.success() } catch (_: Exception) { Result.retry() }
    }
}
