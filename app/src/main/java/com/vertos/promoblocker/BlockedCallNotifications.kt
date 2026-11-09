package com.vertos.promoblocker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.util.UUID

internal const val ACTION_OPEN_BLOCKED_HISTORY = "com.vertos.promoblocker.OPEN_BLOCKED_HISTORY"

internal class BlockedCallNotifications(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun settingsIntent(): Intent {
        val notificationManager = manager
        if (notificationManager?.areNotificationsEnabled() == true) {
            notificationManager.createNotificationChannel(channel())
            return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun post(timestamp: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val notificationManager = manager ?: return
        if (!notificationManager.areNotificationsEnabled()) return
        notificationManager.createNotificationChannel(channel())
        if (notificationManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return

        val openHistory = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BLOCKED_HISTORY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Call blocked")
            .setContentText("Tap to see blocked calls.")
            .setWhen(timestamp)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openHistory)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(UUID.randomUUID().toString(), 0, notification)
        } catch (_: SecurityException) {
            // The user can revoke notification permission while a call is being handled.
        }
    }

    private fun channel() = NotificationChannel(CHANNEL_ID, "Blocked calls", NotificationManager.IMPORTANCE_LOW)

    companion object {
        private const val CHANNEL_ID = "blocked_calls"
    }
}
