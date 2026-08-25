package com.bharath.locationtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AlarmService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val timeout = Executors.newSingleThreadScheduledExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(2001, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Find my phone alarm")
            .setContentText("Remote alarm is active")
            .setOngoing(true)
            .build())

        if (ringtone == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0))
            timeout.schedule({ stopSelf() }, 5, TimeUnit.MINUTES)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        timeout.shutdownNow()
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Find My Phone Alarm", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val CHANNEL = "find_my_phone_alarm" }
}
