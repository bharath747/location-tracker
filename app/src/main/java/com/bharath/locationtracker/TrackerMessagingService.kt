package com.bharath.locationtracker

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TrackerMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        updateToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val action = message.data["action"]?.uppercase() ?: return
        val commandId = message.data["commandId"]
        val deviceId = LocationRepository.getDeviceId(this)
        val commandRef = commandId?.let { FirebaseFirestore.getInstance().collection("devices").document(deviceId).collection("commands").document(it) }
        commandRef?.update("status", "RECEIVED", "receivedAt", FieldValue.serverTimestamp())
        when (action) {
            "RING" -> {
                ContextCompat.startForegroundService(this, Intent(this, AlarmService::class.java))
                commandRef?.update("status", "EXECUTED", "executedAt", FieldValue.serverTimestamp())
            }
            "STOP_RING" -> {
                stopService(Intent(this, AlarmService::class.java))
                commandRef?.update("status", "EXECUTED", "executedAt", FieldValue.serverTimestamp())
            }
            "FETCH_LOCATION" -> CoroutineScope(Dispatchers.IO).launch {
                commandRef?.update("status", "PROCESSING")
                runCatching { LocationRepository.fetchAndUpload(this@TrackerMessagingService) }
                    .onSuccess { commandRef?.update("status", "EXECUTED", "executedAt", FieldValue.serverTimestamp()) }
                    .onFailure { commandRef?.update("status", "FAILED", "error", it.message ?: "Location fetch failed") }
            }
        }
    }

    private fun updateToken(token: String) {
        val id = LocationRepository.getDeviceId(this)
        FirebaseFirestore.getInstance().collection("devices").document(id).set(
            mapOf("fcmToken" to token, "tokenUpdatedAt" to FieldValue.serverTimestamp()),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }
}