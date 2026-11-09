package com.vertos.promoblocker

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.SnackbarHostState

class MainActivity : ComponentActivity() {
    private val historyRequests = Channel<Unit>(Channel.CONFLATED)
    private val openHistory = historyRequests.receiveAsFlow()

    override fun onStart() {
        super.onStart()
        CloudSyncSchedule.onAppOpened(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_PromoBlocker)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleNotificationIntent(intent)
        enableEdgeToEdge()
        val repository = (application as PromoApplication).repository
        setContent {
            val theme by repository.themeMode.collectAsStateWithLifecycle()
            PromoTheme(theme) { ConnectedBlockerScreen(repository, getSystemService(RoleManager::class.java), openHistory) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent) {
        if (intent.action == ACTION_OPEN_BLOCKED_HISTORY) historyRequests.trySend(Unit)
    }
}

@Composable
private fun ConnectedBlockerScreen(repository: BlockerRepository, roleManager: RoleManager?, openHistory: Flow<Unit>) {
    val context = LocalContext.current
    fun needsNotificationPermission() = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    var needsPermission by remember { mutableStateOf(needsNotificationPermission()) }
    var roleHeld by remember { mutableStateOf(false) }
    val available = roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
    fun refreshRole() { roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshRole()
        needsPermission = needsNotificationPermission()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshRole() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        needsPermission = needsNotificationPermission()
    }
    val settings by repository.settings.collectAsStateWithLifecycle()
    val rules by repository.rules.collectAsStateWithLifecycle()
    val history by repository.history.collectAsStateWithLifecycle()
    val count by repository.count.collectAsStateWithLifecycle()
    val storageError by repository.storageError.collectAsStateWithLifecycle()
    val initialized by repository.initialized.collectAsStateWithLifecycle()
    val theme by repository.themeMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun act(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                snackbar.showSnackbar(if (error is IllegalArgumentException) error.message ?: "Check what you entered and try again." else "Couldn’t save this change. Try again.")
            }
        }
    }
    BlockerScreen(
        state = BlockerUiState(settings, rules, history, count, storageError, initialized, roleHeld, available, theme),
        snackbar = snackbar,
        openHistory = openHistory,
        actions = BlockerActions(
            setFlag = { key, value -> act { repository.setFlag(key, value) } },
            setTheme = { act { repository.setThemeMode(it) } },
            requestRole = { act { roleManager?.let { launcher.launch(it.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) } } },
            addRule = { repository.addRule(it) },
            toggleRule = { id, enabled -> act { repository.toggleRule(id, enabled) } },
            deleteRule = { act { repository.deleteRule(it) } },
            clearHistory = { act { repository.clearHistory() } },
            requestNotificationPermission = if (needsPermission) {
                { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else null,
            manageNotifications = { context.startActivity(BlockedCallNotifications(context).settingsIntent()) },
            refreshCloud = {
                scope.launch {
                    try {
                        repository.refreshCloudRules()
                        snackbar.showSnackbar("You have the latest cloud rules.")
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        snackbar.showSnackbar("Couldn’t check for new rules. You can still use your saved rules.")
                    }
                }
            },
            testNumber = { number ->
                repository.awaitReady()
                withContext(Dispatchers.Default) { NumberEvaluator.evaluate(number, false, repository.snapshot) }
            },
        ),
    )
}
