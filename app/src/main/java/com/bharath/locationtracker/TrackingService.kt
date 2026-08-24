package com.bharath.locationtracker

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class TrackingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(1001, NotificationCompat.Builder(this, "tracking")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location tracking active")
            .setContentText("Tracking interval is controlled by Firebase")
            .build())
        scheduleFromFirebase()
        return START_STICKY
    }
    private fun scheduleFromFirebase() {
        val prefs = getSharedPreferences("tracker", MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("devices").document(id).collection("config").document("tracking")
            .addSnapshotListener { snap, _ ->
                val enabled = snap?.getBoolean("trackingEnabled") ?: false
                val minutes = (snap?.getLong("intervalMinutes") ?: 15L).coerceAtLeast(15L)
                if (enabled) schedule(minutes) else WorkManager.getInstance(this).cancelUniqueWork("location-tracking")
            }
    }
    private fun schedule(minutes: Long) {
        val request = PeriodicWorkRequestBuilder<LocationWorker>(minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("location-tracking", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    private fun createChannel() {
        val channel = NotificationChannel("tracking", "Location Tracking", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class LocationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return Result.retry()
        // Reuse app's foreground fetch path through an explicit service intent; upload implementation follows in next revision.
        return Result.success()
    }
}