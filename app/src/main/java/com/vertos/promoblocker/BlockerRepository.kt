package com.vertos.promoblocker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsStore by preferencesDataStore("screening_settings")
interface ScreeningRuntime {
    val snapshot: ScreeningSnapshot
    suspend fun awaitReady()
    fun record(number: String?, reason: String)
}
class BlockerRepository internal constructor(
    context: Context,
    private val fetchCloudFeed: (String?) -> CloudRuleFeed.Feed?,
) : ScreeningRuntime {
    constructor(context: Context) : this(context, CloudRuleFeed::fetch)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        // Never include phone numbers or pattern text in system logs.
        Log.e("PromoBlocker", "Local storage operation failed: ${error.javaClass.simpleName}")
    })
    private val store = context.applicationContext.settingsStore
    private val blockedCallNotifications = BlockedCallNotifications(context.applicationContext)
    private val database = Room.databaseBuilder(context, BlockerDatabase::class.java, "promo-blocker.db")
        .addMigrations(MIGRATION_1_2).build()
    private val dao = database.dao()
    private val ready = CompletableDeferred<Unit>()
    private val ruleLock = Mutex()
    private val cloudLock = Mutex()
    private var lastCloudFeed: CloudRuleFeed.Feed? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val storageError = mutableError.asStateFlow()
    val themeMode = store.data.map { AppThemeMode.fromStored(it[stringPreferencesKey("theme_mode")]) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(AppThemeMode.DARK)
        }.stateIn(scope, SharingStarted.Eagerly, AppThemeMode.DARK)
    @Volatile override var snapshot = ScreeningSnapshot()
        private set
    private val mutableSettings = MutableStateFlow(ScreeningSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val initialized = mutableReady.asStateFlow()
    val rules = dao.observeRules().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val history = dao.observeHistory().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val count = dao.observeCount().stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)
    init {
        scope.launch {
            try {
                dao.seed(DefaultRules.rules.map { RegexRuleEntity(it.id, it.expression, builtIn = true, transactional = it.transactional) })
                combine(store.data.map { prefs ->
                    ScreeningSettings(
                        prefs[booleanPreferencesKey("enabled")] ?: true,
                        prefs[booleanPreferencesKey("spoofed")] ?: true,
                        prefs[booleanPreferencesKey("unknown")] ?: false,
                        prefs[booleanPreferencesKey("transactional")] ?: false,
                    )
                }.distinctUntilChanged(), dao.observeRules()) { options, stored ->
                    val compiled = stored.filter { it.enabled }.map {
                        val reason = when {
                            it.builtIn -> it.id
                            it.isCloud -> it.description ?: it.expression
                            else -> "Custom: ${it.expression}"
                        }
                        CompiledRule(reason, it.expression, it.transactional)
                    }
                    ScreeningSnapshot(options, compiled)
                }.flowOn(Dispatchers.Default).collect {
                    snapshot = it
                    mutableSettings.value = it.settings
                    mutableReady.value = true
                    ready.complete(Unit)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                snapshot = ScreeningSnapshot(ScreeningSettings(enabled = false), emptyList())
                mutableSettings.value = snapshot.settings
                mutableReady.value = false
                mutableError.value = "Hush couldn’t load your settings. Close and reopen Hush to try again."
                // Do not complete readiness with unsafe defaults when persisted settings cannot be read.
            }
        }
    }
    internal suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        database.close()
    }
    override suspend fun awaitReady() { ready.await() }
    suspend fun refreshCloudRules() = withContext(Dispatchers.IO) {
        cloudLock.withLock {
            val feed = fetchCloudFeed(lastCloudFeed?.etag) ?: lastCloudFeed ?: return@withLock
            dao.replaceCloudRules(feed.rules)
            lastCloudFeed = feed
        }
    }
    suspend fun setThemeMode(mode: AppThemeMode) {
        store.edit { it[stringPreferencesKey("theme_mode")] = mode.name }
    }
    suspend fun setFlag(key: String, enabled: Boolean) {
        require(key in setOf("enabled", "spoofed", "unknown", "transactional"))
        store.edit { it[booleanPreferencesKey(key)] = enabled }
    }
    suspend fun addRule(expression: String) = ruleLock.withLock {
        withContext(Dispatchers.Default) { RuleValidator.validate(expression) }
        require(dao.customRuleCount() < RuleValidator.MAX_RULES) { "You can save up to ${RuleValidator.MAX_RULES} of your own rules." }
        dao.insertRule(RegexRuleEntity(UUID.randomUUID().toString(), expression))
    }
    suspend fun toggleRule(id: String, enabled: Boolean) { dao.toggleRule(id, enabled) }
    suspend fun deleteRule(id: String) { dao.deleteRule(id) }
    suspend fun clearHistory() { dao.clearHistory() }
    override fun record(number: String?, reason: String) {
        val timestamp = System.currentTimeMillis()
        scope.launch {
            try { dao.record(BlockedCallEntity(number = number, reason = reason, timestamp = timestamp)) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = "Hush couldn’t add a blocked call to History."
            }
            blockedCallNotifications.post(timestamp)
        }
    }
}
