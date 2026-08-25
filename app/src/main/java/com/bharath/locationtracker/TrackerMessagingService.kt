package com.bharath.locationtracker

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TrackerMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("devices").whereEqualTo("firebaseUid", uid).get()
            .addOnSuccessListener { docs -> docs.documents.forEach { it.reference.update("fcmToken", token, "tokenUpdatedAt", FieldValue.serverTimestamp()) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["action"]?.uppercase()) {
            "RING" -> ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java))
            "STOP_RING" -> stopService(Intent(this, AlarmService::class.java))
            "FETCH_LOCATION" -> {
                val deviceId = message.data["deviceId"] ?: return
                LocationRepository.fetchAndUploadAsync(this, deviceId)
            }
        }
    }
}