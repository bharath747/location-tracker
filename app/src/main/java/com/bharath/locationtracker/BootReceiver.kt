package com.bharath.locationtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("trackingEnabled", false)) return

        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        } catch (_: Exception) {
            // Android may restrict foreground-service starts after boot on some versions.
            // FCM remains the fallback wake-up path for remote commands.
        }
    }
}
