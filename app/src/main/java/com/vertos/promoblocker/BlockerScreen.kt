package com.vertos.promoblocker

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

internal data class BlockerUiState(
    val settings: ScreeningSettings,
    val rules: List<RegexRuleEntity>,
    val history: List<BlockedCallEntity>,
    val count: Int,
    val storageError: String?,
    val initialized: Boolean,
    val roleHeld: Boolean,
    val roleAvailable: Boolean,
    val theme: AppThemeMode,
)
internal data class BlockerActions(
    val setFlag: (String, Boolean) -> Unit,
    val setTheme: (AppThemeMode) -> Unit,
    val requestRole: () -> Unit,
    val addRule: suspend (String) -> Unit,
    val toggleRule: (String, Boolean) -> Unit,
    val deleteRule: (String) -> Unit,
    val clearHistory: () -> Unit,
    val testNumber: suspend (String) -> String?,
    val refreshCloud: () -> Unit = {},
    val requestNotificationPermission: (() -> Unit)? = null,
    val manageNotifications: () -> Unit = {},
    val enableFeedback: (() -> Unit)? = null,
)
private enum class Destination(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_dashboard), RULES("Rules", R.drawable.ic_rules), HISTORY("History", R.drawable.ic_history),
    SETTINGS("Settings", R.drawable.ic_settings), RULE_TABLE("Display rules", R.drawable.ic_rules),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlockerScreen(
    state: BlockerUiState,
    actions: BlockerActions,
    snackbar: SnackbarHostState,
    openHistory: Flow<Unit> = emptyFlow(),
) {
    var selected by rememberSaveable { mutableStateOf(Destination.HOME) }
    BackHandler(selected == Destination.RULE_TABLE) { selected = Destination.RULES }
    LaunchedEffect(openHistory) { openHistory.collect { selected = Destination.HISTORY } }
    var showingTester by rememberSaveable { mutableStateOf(false) }
    var addingRule by rememberSaveable { mutableStateOf(false) }
    var deletingRule by remember { mutableStateOf<RegexRuleEntity?>(null) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    var requestingRole by rememberSaveable { mutableStateOf(false) }
    var showingHelp by rememberSaveable { mutableStateOf(false) }
    val homeScroll = rememberLazyListState()
    val rulesScroll = rememberLazyListState()
    val tableScroll = rememberLazyListState()
    val historyScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val saveableTabs = rememberSaveableStateHolder()
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val customRules = remember(state.rules) { state.rules.filter { !it.builtIn && !it.isCloud } }
    val builtInRules = remember(state.rules) { state.rules.filter { it.builtIn } }
    val cloudRules = remember(state.rules) { state.rules.filter { it.isCloud } }
    val tableRules = remember(customRules, cloudRules) { customRules + cloudRules }
    val historyGroups = remember(state.history) {
        val zone = ZoneId.systemDefault()
        state.history.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
    }
    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected == Destination.RULE_TABLE) {
                    TextButton(onClick = { selected = Destination.RULES }) { Text("‹ Back") }
                }
                Image(painterResource(R.drawable.hush_icon), contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Hush", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Your phone. Your peace.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            if (selected != Destination.RULE_TABLE) NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                Destination.entries.filter { it != Destination.RULE_TABLE }.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination, onClick = { selected = destination },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = colors.primaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            saveableTabs.SaveableStateProvider(selected.name) {
                LazyColumn(
                    state = when (selected) {
                        Destination.HOME -> homeScroll
                        Destination.RULES -> rulesScroll
                        Destination.HISTORY -> historyScroll
                        Destination.SETTINGS -> settingsScroll
                        Destination.RULE_TABLE -> tableScroll
                    },
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.storageError?.let { message -> item("storage-error") { InfoNotice(message, isError = true) } }
                    when (selected) {
                        Destination.HOME -> {
                            item("status") { ProtectionStatus(state, onEnable = { requestingRole = true }) }
                            item("master") {
                                Panel {
                                    SettingRow("Call blocking", "Block calls using the rules you turn on.", state.settings.enabled,
                                        enabled = state.initialized, onChange = { actions.setFlag("enabled", it) })
                                }
                            }
                            item("stats") { StatCard("Blocked calls", "Saved on this phone", state.count, Modifier.fillMaxWidth()) }
                        }
                        Destination.SETTINGS -> {
                            item("settings-heading") {
                                Text("Settings", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                            }
                            item("appearance") { ThemeSelector(state.theme, actions.setTheme) }
                            item("support") { SupportHush(state.theme.isDark) }
                            item("feedback") {
                                Panel {
                                    SectionTitle("After-call feedback", "Get a silent question after answering an unsaved number. Yes blocks and reports the number to HushDB. Approved reports become public rules. No stops future questions for that number.")
                                    actions.enableFeedback?.let { enable ->
                                        FilledTonalButton(onClick = enable) { Text("Allow call feedback") }
                                    }
                                }
                            }
                            item("notifications") {
                                Panel {
                                    SectionTitle("Blocked call alerts", "Get a silent notification when Hush blocks a call.")
                                    actions.requestNotificationPermission?.let { request ->
                                        FilledTonalButton(onClick = request) { Text("Allow notifications") }
                                    }
                                    TextButton(onClick = actions.manageNotifications) { Text("Notification settings") }
                                }
                            }
                        }
                        Destination.RULES -> {
                            item("rules-heading") { PageHeading("Rules", "Choose which calls to block.") }
                            item("tester-button") {
                                FilledTonalButton(onClick = { showingTester = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Try a number")
                                }
                            }
                            item("filters") {
                                Panel {
                                    SectionTitle("What to block", "Turn on the types of calls you want to block.")
                                    SettingRow("Flagged numbers", "Block numbers your phone company flags as possibly fake. Works on Android 11 or later.",
                                        state.settings.blockSpoofed, enabled = state.initialized,
                                        onChange = { actions.setFlag("spoofed", it) })
                                    HorizontalDivider(color = colors.outlineVariant)
                                    SettingRow("Hidden or unknown numbers", "Block calls with no visible number when Android lets Hush check them.",
                                        state.settings.blockUnknown, enabled = state.initialized,
                                        onChange = { actions.setFlag("unknown", it) })
                                    HorizontalDivider(color = colors.outlineVariant)
                                    SettingRow("160 service calls", "These may be useful calls from services you use.",
                                        state.settings.blockTransactional, enabled = state.initialized,
                                        onChange = { actions.setFlag("transactional", it) })
                                }
                            }
                            item("manage-rules") {
                                Panel {
                                    SectionTitle("Local and cloud rules", "${customRules.size} local · ${cloudRules.size} cloud")
                                    FilledTonalButton(onClick = { selected = Destination.RULE_TABLE }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Display rules")
                                    }
                                    TextButton(onClick = { addingRule = true }) { Text("Add rule") }
                                }
                            }
                            item("help") { TextButton(onClick = { showingHelp = true }) {
                                Icon(painterResource(R.drawable.ic_info), null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("How rules work")
                            } }
                            item("cloud-heading") {
                                Panel {
                                    SectionTitle("Cloud rules", "Get rules shared by other people and checked by Hush. Saved rules work offline.")
                                    Text("${cloudRules.size} saved", style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    ) {
                                        TextButton(onClick = actions.refreshCloud) { Text("Check for new rules") }
                                        TextButton(onClick = { uriHandler.openUri(CloudRuleFeed.SITE) }) { Text("Suggest a rule") }
                                    }
                                }
                            }
                            item("builtin-heading") { SectionHeading("Built-in rules", "140 sales calls and 160 service calls") }
                            items(builtInRules, key = { it.id }, contentType = { "rule" }) { rule ->
                                RuleCard(rule, state.settings.blockTransactional, state.initialized,
                                    onToggle = { actions.toggleRule(rule.id, it) }, onDelete = null)
                            }
                        }
                        Destination.RULE_TABLE -> {
                            item("table-heading") {
                                PageHeading("Local and cloud rules", "Tap ON or OFF to change a rule. Tap a local regex to remove it.")
                                TextButton(onClick = { addingRule = true }) { Text("Add rule") }
                            }
                            item("table-columns") { RuleTableColumns() }
                            if (tableRules.isEmpty()) item("empty-table") {
                                InfoNotice("No rules yet. Add a rule or check for cloud rules.")
                            }
                            items(tableRules.size, key = { tableRules[it].id }) { index ->
                                val rule = tableRules[index]
                                RuleTableRow(index + 1, rule, state.initialized, actions.toggleRule) { deletingRule = rule }
                            }
                        }
                        Destination.HISTORY -> {
                            item("history-heading") { PageHeading("Blocked calls", "See what Hush blocked and why.") }
                            item("history-summary") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${state.count} blocked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Shows the latest 1,000. Saves up to 10,000.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    IconButton(enabled = state.count > 0, onClick = { clearing = true }) {
                                        Icon(painterResource(R.drawable.ic_delete), "Clear history")
                                    }
                                }
                            }
                            if (state.history.isEmpty()) item("empty-history") {
                                EmptyCard(R.drawable.ic_history, "No blocked calls yet", "Blocked calls will show up here.")
                            }
                            historyGroups.forEach { (day, calls) ->
                                item("day-$day") { Text(dayLabel(day), style = MaterialTheme.typography.labelLarge,
                                    color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                                items(calls, key = { it.id }, contentType = { "call" }) { call -> HistoryCard(call) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showingTester) NumberTesterDialog(state, actions.testNumber, onDismiss = { showingTester = false })
    if (addingRule) AddRuleDialog(onDismiss = { addingRule = false }, onSave = actions.addRule)
    deletingRule?.let { rule -> ConfirmDialog(
        title = "Remove this rule?", message = "${rule.expression}\n\nHush will stop using this rule to block calls.",
        confirmLabel = "Remove rule", onDismiss = { deletingRule = null },
        onConfirm = { deletingRule = null; actions.deleteRule(rule.id) },
    ) }
    if (clearing) ConfirmDialog(
        title = "Clear your history?", message = "This deletes your list of blocked calls from this phone.",
        confirmLabel = "Clear history", onDismiss = { clearing = false }, onConfirm = { clearing = false; actions.clearHistory() },
    )
    if (requestingRole) SetupDialog(
        onDismiss = { requestingRole = false },
        onContinue = { requestingRole = false; actions.requestRole() },
    )
    if (showingHelp) AlertDialog(
        onDismissRequest = { showingHelp = false }, title = { Text("How rules work") },
        text = { Text("A rule uses a pattern to match the whole phone number. Include any country code or spaces in the number.\n\nFor example, ^0?140\\d+$ matches numbers that start with 140 or 0140 and have more digits after that.\n\nUse Try a number to check your rule. You can save up to 100 rules, with 256 characters each.\n\nAndroid usually skips Hush for saved contacts and may skip it for hidden numbers.") },
        confirmButton = { TextButton(onClick = { showingHelp = false }) { Text("Got it") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Panel {
        Text("App theme", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(selected.label, modifier = Modifier.padding(horizontal = 32.dp), textAlign = TextAlign.Center)
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(maxWidth),
            ) {
                for (theme in AppThemeMode.entries) {
                    val isSelected = selected == theme
                    DropdownMenuItem(
                        text = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(theme.label, Modifier.padding(horizontal = 32.dp), textAlign = TextAlign.Center)
                                if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                                    modifier = Modifier.align(Alignment.CenterEnd).size(20.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(theme)
                        },
                        modifier = Modifier.semantics { this.selected = isSelected },
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportHush(dark: Boolean) {
    val uri = LocalUriHandler.current
    Panel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = { uri.openUri("https://hush.arpanm.xyz") }) {
                AnimatedHushAddress(dark)
            }
            Text(
                "Hush is free and open source. If it helps you, a donation helps me keep working on it. Thank you for supporting Hush.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(
                onClick = { uri.openUri("https://hush.arpanm.xyz/donate") },
                modifier = Modifier.clip(CircleShape).background(
                    Brush.horizontalGradient(listOf(Color(0xFF4E259E), Color(0xFF8E285E)))
                ),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
            ) {
                Icon(painterResource(R.drawable.ic_heart), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Donate")
            }
        }
    }
}

@Composable
private fun AnimatedHushAddress(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "Hush website colors")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "Rainbow position",
    )
    val rainbow = remember(dark) {
        if (dark) listOf(Color(0xFFFFA1AC), Color(0xFFFFD98F), Color(0xFF9BEBC1),
            Color(0xFF9CD9FF), Color(0xFFD7AEFF), Color(0xFFFFA1AC))
        else listOf(Color(0xFFA3214A), Color(0xFF8C4800), Color(0xFF146440),
            Color(0xFF1856A0), Color(0xFF6D28A8), Color(0xFFA3214A))
    }
    var textWidth by remember { mutableIntStateOf(1) }
    val width = textWidth.coerceAtLeast(1).toFloat()
    val offset = width * phase.value
    val gradient = Brush.horizontalGradient(rainbow, startX = -offset, endX = width - offset,
        tileMode = TileMode.Repeated)
    Text(
        "hush.arpanm.xyz",
        style = MaterialTheme.typography.titleMedium.copy(brush = gradient, fontWeight = FontWeight.Bold),
        modifier = Modifier.onSizeChanged { textWidth = it.width },
    )
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProtectionStatus(state: BlockerUiState, onEnable: () -> Unit) {
    val active = state.roleHeld && state.initialized && state.settings.enabled
    val title = when {
        !state.roleAvailable -> "Call blocking unavailable"
        !state.roleHeld -> "Set up call blocking"
        !state.initialized && state.storageError != null -> "Call blocking is off"
        !state.initialized -> "Getting Hush ready"
        !state.settings.enabled -> "Call blocking is off"
        else -> "Call blocking is on"
    }
    val subtitle = when {
        !state.roleAvailable -> "Android doesn’t let Hush block calls on this phone."
        !state.roleHeld -> "Choose Hush in Android settings to start blocking calls."
        !state.initialized && state.storageError != null -> "Hush is letting all calls through."
        !state.initialized -> "Hush lets calls through while your settings load."
        !state.settings.enabled -> "Hush is letting all calls through."
        else -> "Hush blocks calls that match your rules."
    }
    val colors = MaterialTheme.colorScheme
    val background = if (active) colors.primaryContainer else colors.surfaceContainer
    Surface(color = background, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_shield), null, tint = if (active) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(44.dp))
                Surface(color = colors.surface.copy(alpha = .65f), shape = CircleShape) {
                    Text(if (active) "ON" else "OFF",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (!state.roleHeld && state.roleAvailable) Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text("Turn on call blocking") }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), content = content)
    }
}
@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
@Composable
private fun StatCard(title: String, subtitle: String, value: Int, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun InfoNotice(message: String, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (isError) colors.errorContainer else colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_info), null, tint = if (isError) colors.onErrorContainer else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.onErrorContainer else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun RuleCard(rule: RegexRuleEntity, transactionalEnabled: Boolean, initialized: Boolean, onToggle: (Boolean) -> Unit, onDelete: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (rule.builtIn) callReasonLabel(rule.id) else if (rule.isCloud) "Cloud rule" else "Your rule",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (!rule.enabled) "Off" else if (rule.transactional && !transactionalEnabled) "Turn on 160 service calls to use this rule" else "On",
                        style = MaterialTheme.typography.bodySmall, color = if (rule.enabled && (!rule.transactional || transactionalEnabled)) colors.primary else colors.onSurfaceVariant)
                }
                Switch(checked = rule.enabled, enabled = initialized, onCheckedChange = onToggle,
                    modifier = Modifier.semanticsRuleLabel(rule))
            }
            Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                SelectionContainer { Text(rule.expression, modifier = Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            rule.description?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            onDelete?.let { action -> TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(painterResource(R.drawable.ic_delete), null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Remove rule")
            } }
        }
    }
}

private fun Modifier.semanticsRuleLabel(rule: RegexRuleEntity): Modifier = this.then(
    Modifier.semantics { contentDescription = "Rule ${rule.expression}" }
)

private data class NumberTestResult(val reason: String?, val enabled: Boolean)
@Composable
private fun NumberTesterDialog(
    state: BlockerUiState,
    testNumber: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var sample by rememberSaveable { mutableStateOf("") }
    var result by remember(sample, state.settings, state.rules) { mutableStateOf<NumberTestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var error by remember(sample) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = LocalFocusManager.current
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Try a number", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_decline), "Close")
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text("Check if Hush would block this number.", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(
                        value = sample, onValueChange = { sample = it.take(NumberEvaluator.MAX_NUMBER_LENGTH) },
                        enabled = !testing, label = { Text("Phone number") }, placeholder = { Text("+911401234567") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = MaterialTheme.shapes.small,
                    )
                    FilledTonalButton(enabled = state.initialized && sample.isNotBlank() && !testing,
                        onClick = {
                            focus.clearFocus()
                            error = null
                            testing = true
                            scope.launch {
                                try { result = NumberTestResult(testNumber(sample), state.settings.enabled) }
                                catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    error = "Couldn’t check this number. Try again."
                                } finally { testing = false }
                            }
                        }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
                        if (testing) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (testing) "Checking…" else "Test number")
                    }
                    result?.let { verdict ->
                        val declined = verdict.reason != null
                        val colors = MaterialTheme.colorScheme
                        val allowedBackground = if (state.theme.isDark) Color(0xFF163121) else Color(0xFFE6F7EC)
                        val allowedAccent = if (state.theme.isDark) Color(0xFFABEDBF) else Color(0xFF15572F)
                        val accent = if (declined) colors.onErrorContainer else allowedAccent
                        Surface(
                            color = if (declined) colors.errorContainer else allowedBackground,
                            contentColor = accent,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Surface(color = accent.copy(alpha = .12f), contentColor = accent, shape = CircleShape) {
                                    Icon(
                                        painterResource(if (declined) R.drawable.ic_decline else R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.padding(8.dp).size(20.dp),
                                    )
                                }
                                Text(
                                    if (declined) "Hush would block this call" else "Hush would let this call through",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    verdict.reason?.let(::callReasonLabel)
                                        ?: if (!verdict.enabled) "Call blocking is off." else "None of your rules block this number.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var expression by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Add a rule", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Hush blocks numbers that match this pattern when the rule and call blocking are on.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = expression, onValueChange = { expression = it.take(RuleValidator.MAX_LENGTH); error = null },
                        label = { Text("Number pattern") }, placeholder = { Text("^0?140\\d+$") },
                        enabled = !saving, isError = error != null, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        supportingText = { Text(error ?: "${expression.length}/${RuleValidator.MAX_LENGTH} characters") },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !saving && expression.isNotBlank(), onClick = {
                        saving = true
                        scope.launch {
                            try { onSave(expression); onDismiss() }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = if (failure is IllegalArgumentException) failure.message else "Couldn’t save this rule. Try again."
                            } finally { saving = false }
                        }
                    }) { Text(if (saving) "Saving…" else "Save rule") }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(@DrawableRes icon: Int, title: String, message: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape) {
                Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(18.dp).size(30.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun HistoryCard(call: BlockedCallEntity) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = colors.errorContainer, shape = CircleShape) {
                Icon(painterResource(R.drawable.ic_decline), null, tint = colors.onErrorContainer, modifier = Modifier.padding(10.dp).size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(call.number ?: "Hidden or unknown number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                Text("Reason · " + callReasonLabel(call.reason), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(remember(call.timestamp) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(call.timestamp)) },
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
private fun callReasonLabel(reason: String): String = when (reason) {
    DefaultRules.SALES_WITH_COUNTRY_CODE -> "140 sales calls (with country code)"
    DefaultRules.SALES_WITHOUT_COUNTRY_CODE -> "140 sales calls (without country code)"
    DefaultRules.SERVICE_WITH_COUNTRY_CODE -> "160 service calls (with country code)"
    DefaultRules.SERVICE_WITHOUT_COUNTRY_CODE -> "160 service calls (without country code)"
    NumberEvaluator.SPOOFED -> "Flagged by your phone company"
    NumberEvaluator.UNKNOWN -> "Hidden or unknown number"
    CLOUD_FEEDBACK_DESCRIPTION -> "Reported by the Hush community"
    else -> reason
}

private fun dayLabel(day: LocalDate): String = when (day) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
}
@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RuleTableColumns() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SL No.", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall)
        Text("Regex", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Description", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Status", Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RuleTableRow(
    index: Int,
    rule: RegexRuleEntity,
    initialized: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = colors.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(index.toString(), Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 2.dp)) {
                if (rule.isCloud) {
                    SelectionContainer { Text(rule.expression, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(
                        rule.expression,
                        Modifier.clickable(onClickLabel = "Remove local rule", onClick = onDelete),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(rule.description ?: "Local rule", Modifier.weight(1f).padding(4.dp), style = MaterialTheme.typography.bodySmall)
            Box(
                Modifier.width(52.dp).heightIn(min = 48.dp).toggleable(
                    value = rule.enabled, enabled = initialized, role = Role.Switch,
                    onValueChange = { onToggle(rule.id, it) },
                ).semantics { contentDescription = "Status for rule ${rule.expression}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (rule.enabled) "ON" else "OFF", color = if (rule.enabled) colors.primary else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SetupDialog(onDismiss: () -> Unit, onContinue: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(painterResource(R.drawable.hush_icon), null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                Text("Set up Hush", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Android lets you choose one caller ID and spam app. Your Phone app still makes and answers calls.",
                    style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("1. Choose Hush in the Android prompt", fontWeight = FontWeight.SemiBold)
                Text("2. Check Home for “Call blocking is on”", fontWeight = FontWeight.SemiBold)
                Text("You can turn on after-call feedback later in Settings.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
            }
        }
    }
}
