package com.bharath.locationtracker

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
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
        getSharedPreferences("tracker", Context.MODE_PRIVATE).edit()
            .putBoolean("trackingEnabled", true).apply()
        createChannel()
        startForeground(1001, NotificationCompat.Builder(this, "tracking")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location tracking active")
            .setContentText("Hybrid command delivery active")
            .setOngoing(true)
            .build())
        TrackerRuntimeStatus.commandListenerStatus = "CONNECTING"
        scheduleFromFirebase()
        listenForCommands()
        return START_STICKY
    }

    private fun scheduleFromFirebase() {
        val id = LocationRepository.getDeviceId(this)
        listener?.remove()
        listener = FirebaseFirestore.getInstance().collection("devices").document(id)
            .collection("config").document("tracking")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    TrackerRuntimeStatus.commandListenerStatus = "CONFIG ERROR: ${error.message ?: "unknown"}"
                    return@addSnapshotListener
                }
                val enabled = snap?.getBoolean("trackingEnabled") ?: true
                val minutes = (snap?.getLong("intervalMinutes") ?: 15L).coerceAtLeast(15L)
                if (enabled) schedule(minutes)
                else WorkManager.getInstance(this).cancelUniqueWork("location-tracking")
            }
    }

    private fun listenForCommands() {
        val ref = FirebaseFirestore.getInstance().collection("devices")
            .document(LocationRepository.getDeviceId(this))
        commandListener?.remove()
        TrackerRuntimeStatus.commandListenerStatus = "CONNECTING"
        commandListener = ref.collection("commands")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    TrackerRuntimeStatus.commandListenerStatus = "ERROR: ${error.message ?: "unknown"}"
                    return@addSnapshotListener
                }
                TrackerRuntimeStatus.commandListenerStatus = "ACTIVE"
                snapshots?.documents?.forEach { command ->
                    val action = command.getString("action")?.uppercase() ?: return@forEach
                    TrackerRuntimeStatus.lastCommand = action
                    TrackerRuntimeStatus.lastCommandResult = "RECEIVED"
                    executeCommand(command.id, action)
                }
            }
    }

    private fun executeCommand(commandId: String, action: String) {
        val ref = FirebaseFirestore.getInstance().collection("devices")
            .document(LocationRepository.getDeviceId(this))
            .collection("commands").document(commandId)

        ref.update(
            mapOf(
                "status" to "PROCESSING",
                "receivedAt" to FieldValue.serverTimestamp()
            )
        ).addOnFailureListener {
            TrackerRuntimeStatus.lastCommandResult = "FAILED TO CLAIM: ${it.message ?: "unknown"}"
        }

        TrackerRuntimeStatus.lastCommandResult = "PROCESSING"
        when (action) {
            "RING" -> {
                ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java))
                ref.update("status", "EXECUTED", "executedAt", FieldValue.serverTimestamp())
                TrackerRuntimeStatus.lastCommandResult = "EXECUTED"
            }
            "STOP_RING" -> {
                stopService(Intent(this, AlarmService::class.java))
                ref.update("status", "EXECUTED", "executedAt", FieldValue.serverTimestamp())
                TrackerRuntimeStatus.lastCommandResult = "EXECUTED"
            }
            "FETCH_LOCATION" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { LocationRepository.fetchAndUpload(this@TrackingService) }
                        .onSuccess {
                            ref.update("status", "EXECUTED", "executedAt", FieldValue.serverTimestamp())
                            TrackerRuntimeStatus.lastCommandResult = "EXECUTED"
                        }
                        .onFailure { e ->
                            ref.update("status", "FAILED", "error", e.message ?: "Location fetch failed")
                            TrackerRuntimeStatus.lastCommandResult = "FAILED: ${e.message ?: "Location fetch failed"}"
                        }
                }
            }
            else -> {
                ref.update("status", "IGNORED", "executedAt", FieldValue.serverTimestamp())
                TrackerRuntimeStatus.lastCommandResult = "IGNORED"
            }
        }
    }

    private fun schedule(minutes: Long) {
        val request = PeriodicWorkRequestBuilder<LocationWorker>(minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "location-tracking", ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracking", "Location Tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        listener?.remove()
        commandListener?.remove()
        TrackerRuntimeStatus.commandListenerStatus = "STOPPED"
        WorkManager.getInstance(this).cancelUniqueWork("location-tracking")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class LocationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext Result.failure()
        try {
            LocationRepository.fetchAndUpload(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
