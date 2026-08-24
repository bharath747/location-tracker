package com.bharath.locationtracker
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class TrackingService : Service() {
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(1001, NotificationCompat.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Location tracking active").setContentText("Interval controlled by Firebase").build())
        scheduleFromFirebase()
        return START_STICKY
    }
    private fun scheduleFromFirebase() {
        val id = LocationRepository.getDeviceId(this)
        listener?.remove()
        listener = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("devices").document(id).collection("config").document("tracking").addSnapshotListener { snap, _ ->
            val enabled = snap?.getBoolean("trackingEnabled") ?: true
            val minutes = (snap?.getLong("intervalMinutes") ?: 15L).coerceAtLeast(15L)
            if (enabled) schedule(minutes) else WorkManager.getInstance(this).cancelUniqueWork("location-tracking")
        }
    }
    private fun schedule(minutes: Long) {
        val request = PeriodicWorkRequestBuilder<LocationWorker>(minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("location-tracking", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tracking", "Location Tracking", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onDestroy() { listener?.remove(); WorkManager.getInstance(this).cancelUniqueWork("location-tracking"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
class LocationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext Result.failure()
        try { LocationRepository.fetchAndUpload(applicationContext); Result.success() }
        catch (_: Exception) { Result.retry() }
    }
}