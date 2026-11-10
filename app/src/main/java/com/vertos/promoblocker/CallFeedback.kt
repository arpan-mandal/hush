package com.vertos.promoblocker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.work.*
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

internal const val CLOUD_FEEDBACK_DESCRIPTION = "Autonomous user submission scam/spam calls"
private const val YES = "com.vertos.promoblocker.FEEDBACK_YES"
private const val NO = "com.vertos.promoblocker.FEEDBACK_NO"
private val feedbackNumberFormat = Regex("^\\+?[0-9]{7,15}$")

internal fun feedbackNumber(raw: String?): String? {
    val number = raw?.filterNot { it in " -()." } ?: return null
    return number.takeIf { feedbackNumberFormat.matches(it) }
}

internal fun exactFeedbackRule(number: String): String = "^" + number.replace("+", "\\+") + "$"

internal class CallFeedbackTracker {
    private var generation = 0L
    private var candidate: String? = null
    private var allowedAt = 0L
    private var ringingAt = 0L
    private var ambiguous = false
    private var phase = TelephonyManager.EXTRA_STATE_IDLE

    @Synchronized fun beginCall(): Long {
        // A second screened call makes the aggregate phone-state signal ambiguous.
        if (candidate != null && phase != TelephonyManager.EXTRA_STATE_IDLE) ambiguous = true
        candidate = null
        return ++generation
    }

    @Synchronized fun allowed(number: String?, token: Long) {
        val now = SystemClock.elapsedRealtime()
        val ringingJustStarted = phase == TelephonyManager.EXTRA_STATE_RINGING && now - ringingAt <= 2_000
        if (generation == token && !ambiguous && (phase == TelephonyManager.EXTRA_STATE_IDLE || ringingJustStarted)) {
            candidate = feedbackNumber(number)
            allowedAt = now
        }
    }

    @Synchronized fun stateChanged(state: String): String? {
        val previous = phase
        phase = state
        return when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (previous != TelephonyManager.EXTRA_STATE_RINGING) ringingAt = SystemClock.elapsedRealtime()
                if (previous == TelephonyManager.EXTRA_STATE_OFFHOOK ||
                    SystemClock.elapsedRealtime() - allowedAt > 10_000) candidate = null
                null
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (previous != TelephonyManager.EXTRA_STATE_RINGING &&
                    previous != TelephonyManager.EXTRA_STATE_OFFHOOK) candidate = null
                null
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val ended = candidate.takeIf { previous == TelephonyManager.EXTRA_STATE_OFFHOOK }
                candidate = null
                ambiguous = false
                ended
            }
            else -> null
        }
    }
}

class CallFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = (context.applicationContext as PromoApplication).repository
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val number = repository.phoneStateChanged(state) ?: return
            completeAsync { repository.promptForFeedback(number) }
            return
        }
        if (intent.action != YES && intent.action != NO) return
        val number = feedbackNumber(intent.data?.schemeSpecificPart) ?: return
        completeAsync { repository.respondToFeedback(number, intent.action == YES) }
    }

    private fun completeAsync(action: suspend () -> Unit) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A failed save leaves the notification visible so the user can retry.
            } finally {
                result.finish()
            }
        }
    }
}

internal class CallFeedbackNotifications(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = "call_feedback"

    fun available(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            manager?.areNotificationsEnabled() == true &&
            manager?.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    @SuppressLint("MissingPermission")
    fun post(number: String) {
        val notifications = manager ?: return
        if (!available()) return
        notifications.createNotificationChannel(
            NotificationChannel(channel, "Post-call spam feedback", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Was this call spam or a scam?")
            .setContentText("$number · Yes blocks and reports this number")
            .setStyle(Notification.BigTextStyle().bigText("$number\nYes blocks and reports this number. Approved reports become public rules."))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(action("Yes", YES, 0xFFCC4747.toInt(), number))
            .addAction(action("No", NO, 0xFF368050.toInt(), number))
            .build()
        try {
            notifications.notify(number, 1, notification)
        } catch (_: SecurityException) {
            // Permissions can change after the call ends.
        }
    }

    fun dismiss(number: String) { manager?.cancel(number, 1) }

    private fun action(label: String, action: String, color: Int, number: String): Notification.Action {
        val text = SpannableString(label).apply {
            setSpan(ForegroundColorSpan(color), 0, length, 0)
        }
        val intent = Intent(context, CallFeedbackReceiver::class.java)
            .setAction(action)
            .setData(Uri.fromParts("hush-feedback", number, null))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, text, pending).build()
    }
}

@SuppressLint("HardwareIds")
internal fun feedbackDeviceId(context: Context): String? {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: return null
    return MessageDigest.getInstance("SHA-256").digest("hush-feedback:$id".toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal object FeedbackApi {
    fun latestVersion(): String? = try {
        request("/checkVersion") { connection ->
            if (connection.responseCode != 200) null else {
                val data = connection.inputStream.use { stream ->
                    val buffer = ByteArray(128)
                    var size = 0
                    while (size < buffer.size) {
                        val read = stream.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                    if (size == buffer.size) throw IOException("Version response too large")
                    String(buffer, 0, size, Charsets.UTF_8)
                }
                JSONObject(data).getString("version").takeIf { Regex("^\\d+\\.\\d+\\.\\d+$").matches(it) }
            }
        }
    } catch (_: Exception) { null }

    fun report(number: String, device: String): Boolean = try {
        request("/api/v1/feedback") { connection ->
            val expression = URLEncoder.encode(exactFeedbackRule(number), "UTF-8")
            val body = "regex=$expression&device=$device".toByteArray()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            when (connection.responseCode) {
                204, 400 -> true
                else -> false
            }
        }
    } catch (_: IOException) { false }

    private fun <T> request(path: String, action: (HttpsURLConnection) -> T): T {
        val connection = URL(CloudRuleFeed.SITE + path).openConnection() as HttpsURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        return try { action(connection) } finally { connection.disconnect() }
    }
}

class CallFeedbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as PromoApplication).repository
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (repository.sendPendingFeedback()) Result.success() else Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val work = OneTimeWorkRequestBuilder<CallFeedbackWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("hush-feedback", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
