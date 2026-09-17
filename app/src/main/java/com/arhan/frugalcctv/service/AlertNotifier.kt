package com.arhan.frugalcctv.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object AlertNotifier {
    private const val CHANNEL_ID = "frugal_security_alerts"
    private const val CHANNEL_NAME = "Security alerts"
    private const val NOTIFICATION_ID = 2001

    fun notify(context: Context, title: String, message: String, playTone: Boolean = true) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "FrugalCCTV AI and security alerts"
            enableVibration(true)
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
        }
        manager.createNotificationChannel(channel)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .build()
            )
        }

        if (playTone) {
            runCatching {
                ToneGenerator(AudioManager.STREAM_ALARM, 85).also { tone ->
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
                }
            }
        }
    }
}
