package com.bharath.locationtracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocationRepository {
    fun getDeviceId(context: Context): String {
        return context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
            .getString("deviceId", null)
            ?.trim()
            .orEmpty()
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchAndUpload(context: Context): String {
        val deviceId = getDeviceId(context)
        if (deviceId.isBlank()) throw IllegalStateException("Device is not registered")

        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = suspendCancellableCoroutine<Location> { cont ->
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) cont.resume(loc)
                    else cont.resumeWithException(IllegalStateException("Location unavailable"))
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val data = hashMapOf<String, Any?>(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "batteryLevel" to battery,
            "isCharging" to charging,
            "lastSeen" to FieldValue.serverTimestamp()
        )

        withContext(Dispatchers.IO) {
            val doc = FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            Tasks.await(doc.set(data, com.google.firebase.firestore.SetOptions.merge()), 30, TimeUnit.SECONDS)
            Tasks.await(doc.collection("locationHistory").add(data + mapOf("recordedAt" to FieldValue.serverTimestamp())), 30, TimeUnit.SECONDS)
        }

        return String.format(Locale.US, "Latitude: %.6f\nLongitude: %.6f\nAccuracy: %.1f m", location.latitude, location.longitude, location.accuracy)
    }
}
