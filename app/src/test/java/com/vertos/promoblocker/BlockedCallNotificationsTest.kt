package com.vertos.promoblocker

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BlockedCallNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    @Config(sdk = [29, 35])
    fun eachRecordedDeclinePostsAQuietNotificationThatOpensHistory() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val repository = BlockerRepository(context)
        try {
            repository.record("+911408823412", "Promotional")
            repository.record(null, "Private caller")
            withTimeout(10_000) {
                while (manager.activeNotifications.size < 2) delay(10)
            }
            for (entry in manager.activeNotifications) {
                val notification = entry.notification
                assertEquals("Call blocked", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
                assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("+911408823412"))
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(notification.channelId).importance)
                val intent = shadowOf(notification.contentIntent).savedIntent
                assertEquals(ACTION_OPEN_BLOCKED_HISTORY, intent.action)
                assertEquals(MainActivity::class.java.name, intent.component?.className)
            }
        } finally {
            repository.close()
        }
    }

    @Test fun notificationsRespectPermissionAndAppNotificationSetting() {
        val notifications = BlockedCallNotifications(context)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.post(1L)
        assertTrue(manager.activeNotifications.isEmpty())

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.post(2L)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
