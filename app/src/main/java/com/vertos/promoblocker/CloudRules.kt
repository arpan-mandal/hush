package com.vertos.promoblocker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal object CloudRuleFeed {
    const val SITE = "https://hushdb.arpanm.xyz"
    private const val MAX_RULES = 100
    private const val MAX_BYTES = 512 * 1024
    data class Feed(val rules: List<RegexRuleEntity>, val etag: String?)

    fun fetch(etag: String?): Feed? {
        val connection = URL("$SITE/api/v1/rules").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (etag != null) connection.setRequestProperty("If-None-Match", etag)
        try {
            when (val status = connection.responseCode) {
                304 -> return null
                200 -> Unit
                else -> throw IOException("Rule feed HTTP $status")
            }
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_BYTES) throw IOException("Rule feed too large")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            return Feed(parse(bytes.toString(Charsets.UTF_8)), connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    fun parse(payload: String): List<RegexRuleEntity> {
        val json = JSONObject(payload)
        require(json.getInt("version") == 1)
        val rules = json.getJSONArray("rules")
        require(rules.length() <= MAX_RULES)
        val ids = HashSet<Long>()
        val expressions = HashSet<String>()
        return List(rules.length()) { index ->
            val rule = rules.getJSONObject(index)
            val id = rule.getLong("id")
            val regex = rule.getString("regex")
            val description = rule.getString("description")
            require(id > 0 && ids.add(id) && expressions.add(regex))
            require(description.isNotBlank() && description.length <= 240)
            RuleValidator.validate(regex)
            RegexRuleEntity("cloud:$id", regex, description = description)
        }
    }
}

class CloudRuleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as PromoApplication).repository
        return try {
            repository.refreshCloudRules()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.retry()
        }
    }
}

internal object CloudSyncSchedule {
    private val started = AtomicBoolean(false)
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        if (started.compareAndSet(false, true)) {
            val periodic = PeriodicWorkRequestBuilder<CloudRuleWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork("hush-rule-feed", ExistingPeriodicWorkPolicy.KEEP, periodic)
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    private var validatedNetwork: Network? = null
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            if (validatedNetwork != network) {
                                validatedNetwork = network
                                enqueue(app)
                            }
                        } else if (validatedNetwork == network) validatedNetwork = null
                    }
                    override fun onLost(network: Network) {
                        if (validatedNetwork == network) validatedNetwork = null
                    }
                },
            )
        }
        enqueue(app)
    }

    private fun enqueue(context: Context) {
        val work = OneTimeWorkRequestBuilder<CloudRuleWorker>().setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniqueWork("hush-rule-refresh", ExistingWorkPolicy.KEEP, work)
    }
}
