package com.dtcgroup.freezer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview
import com.dtcgroup.freezer.core.ShizukuHelper
import com.dtcgroup.freezer.data.AppInfo
import com.dtcgroup.freezer.data.AppExtras
import com.dtcgroup.freezer.data.PermissionEntry
import com.dtcgroup.freezer.data.ProcessSummary
import com.dtcgroup.freezer.data.UsageSnapshot
import com.dtcgroup.freezer.ui.AppControlViewModel
import com.dtcgroup.freezer.ui.UiState
import com.dtcgroup.freezer.ui.theme.FreezerV1Theme
import kotlinx.coroutines.flow.Flow
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val viewModel: AppControlViewModel by viewModels()

    private val permissionGrantedListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            viewModel.onShizukuPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED)
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            ShizukuHelper.bindIfNeeded()
            viewModel.updateShizukuState()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            viewModel.updateShizukuState()
        }

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ShizukuHelper.attach(applicationContext)

        Shizuku.addRequestPermissionResultListener(permissionGrantedListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FreezerV1Theme {
                AppScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionGrantedListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
        super.onDestroy()
    }
}

// ---- UI ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(viewModel: AppControlViewModel) {
    val state by viewModel.state.collectAsState()

    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        viewModel.vpnConsent.collect { intent -> vpnConsentLauncher.launch(intent) }
    }

    AppScreenContent(
        state = state,
        onGrantShizuku = viewModel::requestShizukuPermission,
        onFreeze = viewModel::toggleFreeze,
        onBlock = viewModel::toggleInternetBlock,
        onOpen = { selectedApp = it },
        onErrorShown = viewModel::errorShown,
    )

    // ---- App details bottom sheet -----------------------------------------

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    selectedApp?.let { app ->
        ModalBottomSheet(
            onDismissRequest = { selectedApp = null },
            sheetState = sheetState,
        ) {
            AppDetailsSheet(
                app = app,
                shizukuReady = state.shizukuReady,
                loadDetails = { viewModel.loadDetails(it) },
                processUpdates = { uid -> viewModel.processUpdates(uid) },
                usageUpdates = { uid -> viewModel.usageUpdates(uid) },
                onForceStop = { viewModel.forceStop(app) },
                onUninstallForUser = { viewModel.uninstallForCurrentUser(app) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppScreenContent(
    state: UiState,
    onGrantShizuku: () -> Unit,
    onFreeze: (AppInfo) -> Unit,
    onBlock: (AppInfo) -> Unit,
    onOpen: (AppInfo) -> Unit = {},
    onErrorShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Local UI filter state (search + type tab)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    val filteredApps = remember(state.apps, query, filter) {
        state.apps.filter { app ->
            val matchesType = when (filter) {
                AppFilter.ALL -> true
                AppFilter.INSTALLED -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
            }
            val matchesQuery = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            matchesType && matchesQuery
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StatusHeader(
                totalApps = state.totalApps,
                frozenApps = state.apps.count { it.isFrozen },
                shizukuReady = state.shizukuReady,
                firewallOn = state.firewallOn,
                onGrantShizuku = onGrantShizuku,
            )
            HorizontalDivider()

            SearchAndFilter(
                query = query,
                onQueryChange = { query = it },
                selected = filter,
                onSelect = { filter = it },
            )

            Text(
                text = "Showing ${filteredApps.size} of ${state.apps.size} apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                // First load: no data yet → centered spinner
                state.isScanning && state.apps.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Scanning installed apps…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // No results after filtering
                filteredApps.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No apps match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Column(Modifier.fillMaxSize()) {
                    // Re-scan of an existing list → thin bar so the list stays visible
                    if (state.isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredApps, key = { it.uid to it.packageName }) { app ->
                            AppRow(
                                app = app,
                                busy = app.packageName in state.busyPackages,
                                shizukuReady = state.shizukuReady,
                                onFreeze = { onFreeze(app) },
                                onBlock = { onBlock(app) },
                                onOpen = { onOpen(app) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(
    totalApps: Int,
    frozenApps: Int,
    shizukuReady: Boolean,
    firewallOn: Boolean,
    onGrantShizuku: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "Total apps: $totalApps • Frozen: $frozenApps",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    shizukuReady -> "Shizuku: READY"
                    else -> "Shizuku: NOT READY"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            if (!shizukuReady) {
                Button(onClick = onGrantShizuku) {
                    Text("Grant Shizuku")
                }
            }
            Spacer(Modifier.width(12.dp))
            if (firewallOn) {
                AssistChip(onClick = {}, label = { Text("Firewall: ON") })
            }
        }
        if (!shizukuReady) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Install & activate Shizuku (ADB or root) to freeze apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    busy: Boolean,
    shizukuReady: Boolean,
    onFreeze: () -> Unit,
    onBlock: () -> Unit,
    onOpen: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            drawable = app.icon,
            initial = app.label.take(1).uppercase(),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                if (app.isFrozen) SmallBadge("FROZEN")
                if (app.isInternetBlocked) SmallBadge("BLOCKED")
                if (app.isSystem) SmallBadge("SYSTEM")
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${app.packageName} • uid ${app.uid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Freeze", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = app.isFrozen,
                onCheckedChange = { onFreeze() },
                enabled = shizukuReady && !busy,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Block", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = app.isInternetBlocked,
                onCheckedChange = { onBlock() },
            )
        }
    }
}

@Composable
private fun AppIcon(drawable: android.graphics.drawable.Drawable?, initial: String) {
    if (drawable != null) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    setImageDrawable(drawable)
                }
            },
            update = { view -> view.setImageDrawable(drawable) },
            modifier = Modifier.size(44.dp),
        )
    } else {
        // Letter-badged fallback so an icon is always visible (also in previews,
        // where no real Drawable exists)
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SmallBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
    )
}

// ---- Details bottom sheet --------------------------------------------------------

@Composable
private fun AppDetailsSheet(
    app: AppInfo,
    shizukuReady: Boolean,
    loadDetails: suspend (String) -> AppExtras?,
    processUpdates: (Int) -> Flow<List<ProcessSummary>>,
    usageUpdates: (Int) -> Flow<UsageSnapshot>,
    onForceStop: () -> Unit = {},
    onUninstallForUser: () -> Unit = {},
) {
    var tabIndex by remember { mutableStateOf(0) }
    var extras by remember { mutableStateOf<AppExtras?>(null) }
    var processes by remember { mutableStateOf<List<ProcessSummary>>(emptyList()) }
    var usage by remember { mutableStateOf<UsageSnapshot?>(null) }

    LaunchedEffect(app.packageName) {
        extras = loadDetails(app.packageName)
    }

    // Poll processes only while their tab is visible.
    if (tabIndex == 1) {
        LaunchedEffect(app.uid) {
            processUpdates(app.uid).collect { processes = it }
        }
    }

    // Poll internet usage only while its tab is visible.
    if (tabIndex == 3) {
        LaunchedEffect(app.uid) {
            usageUpdates(app.uid).collect { usage = it }
        }
    }

    val tabs = listOf("Details", "Processes", "Permissions", "Usage", "Actions")

    // Full-screen sheet by default: content claims all available height,
    // the pinned TabRow stays under the header, and the tab body scrolls.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(drawable = app.icon, initial = app.label.take(1).uppercase())
            Spacer(Modifier.width(12.dp))
            Column {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "uid ${app.uid}${if (app.isSystem) " • system app" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(title) },
                )
            }
        }

        // Body (scrollable per tab)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            when (tabIndex) {
                0 -> DetailsTab(app, extras)
                1 -> ProcessesTab(processes, shizukuReady)
                2 -> PermissionsTab(extras?.permissions)
                3 -> UsageTab(usage)
                else -> ActionsTab(
                    app = app,
                    shizukuReady = shizukuReady,
                    onForceStop = onForceStop,
                    onUninstallForUser = onUninstallForUser,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatTimestamp(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(epochMs))

@Composable
private fun DetailsTab(app: AppInfo, extras: AppExtras?) {
    if (extras == null) {
        Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        DetailRow("Version", extras.versionName ?: "—")
        DetailRow("Version code", extras.versionCode.toString())
        DetailRow("Min SDK", "API ${extras.minSdk}")
        DetailRow("Target SDK", "API ${extras.targetSdk}")
        DetailRow("First install", formatTimestamp(extras.firstInstallTime))
        DetailRow("Last update", formatTimestamp(extras.lastUpdateTime))
        DetailRow("Frozen", if (app.isFrozen) "Yes" else "No")
        DetailRow("Internet", if (app.isInternetBlocked) "Blocked" else "Allowed")
        DetailRow("System app", if (app.isSystem) "Yes" else "No (user installed)")
    }
}

@Composable
private fun ProcessesTab(processes: List<ProcessSummary>, shizukuReady: Boolean) {
    Column {
        Text(
            text = when {
                shizukuReady -> "Live · refreshes every second via Shizuku (ps)"
                else -> "Requires the Shizuku permission to observe processes"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (processes.isEmpty()) {
            Text(
                text = if (shizukuReady) {
                    "No running processes for this app (frozen apps have none)."
                } else {
                    "—"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            processes.forEach { proc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        text = "PID ${proc.pid}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(90.dp),
                    )
                    Text(
                        text = proc.processName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsTab(permissions: List<PermissionEntry>?) {
    if (permissions == null) {
        Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        if (permissions.isEmpty()) {
            Text(
                text = "This app requests no permissions.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val grantedCount = permissions.count { it.granted }
            Text(
                text = "$grantedCount of ${permissions.size} granted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            permissions.forEach { permission ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = permission.shortName.replace('_', ' '),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = permission.fullName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (permission.granted) "GRANTED" else "DENIED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (permission.granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// ---- Usage tab ----------------------------------------------------------------

private fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}

@Composable
private fun UsageTab(usage: UsageSnapshot?) {
    Column {
        Text(
            text = "Internet usage · live (TrafficStats, cumulative since boot)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (usage == null) {
            Text("Sampling…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        DetailRow("Received", humanBytes(usage.rxBytes))
        DetailRow("Sent", humanBytes(usage.txBytes))
        DetailRow("Total", humanBytes(usage.rxBytes + usage.txBytes))

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Current rate",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        DetailRow("Down", "${humanBytes(usage.rxSpeedBytes)}/s")
        DetailRow("Up", "${humanBytes(usage.txSpeedBytes)}/s")

        if (usage.rxBytes <= 0 && usage.txBytes <= 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No traffic recorded since boot (device counters reset at reboot " +
                    "or the app may be frozen/internet-blocked).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- Actions tab -------------------------------------------------------------

@Composable
private fun ActionsTab(
    app: AppInfo,
    shizukuReady: Boolean,
    onForceStop: () -> Unit,
    onUninstallForUser: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val launchable = remember(app.packageName) {
        context.packageManager.getLaunchIntentForPackage(app.packageName) != null
    }

    // System-app removal needs a privilege confirmation dialog.
    var confirmShizukuUninstall by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "Actions target "
                + if (app.isSystem) "a system app (Shizuku required)" else "a user-installed app",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (launchable) {
            Button(
                onClick = {
                    context.packageManager
                        .getLaunchIntentForPackage(app.packageName)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(context::startActivity)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Launch app")
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onForceStop,
            enabled = shizukuReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Force stop (Shizuku)")
        }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                if (app.isSystem) {
                    confirmShizukuUninstall = true
                } else {
                    // Signature user uninstaller dialog — the safest path.
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:${app.packageName}"),
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (app.isSystem) "Remove for current user (Shizuku)"
                else "Uninstall app"
            )
        }

        if (confirmShizukuUninstall) {
            AlertDialog(
                onDismissRequest = { confirmShizukuUninstall = false },
                title = { Text("Remove system app?") },
                text = {
                    Text(
                        "This runs `pm uninstall --user 0 ${app.packageName}` via Shizuku.\n" +
                            "It removes the app for the current user only; other users keep it. " +
                            "Removing critical packages can break the system."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmShizukuUninstall = false
                            onUninstallForUser()
                        }
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmShizukuUninstall = false }
                    ) { Text("Cancel") }
                },
            )
        }
    }
}

// ---- Previews ----------------------------------------------------------------

/** Tabs that filter the list by app origin. */
private enum class AppFilter(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    SYSTEM("System"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilter(
    query: String,
    onQueryChange: (String) -> Unit,
    selected: AppFilter,
    onSelect: (AppFilter) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search apps…") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) {
                        Text("Clear")
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppFilter.entries.forEachIndexed { index, filterOption ->
                SegmentedButton(
                    selected = selected == filterOption,
                    onClick = { onSelect(filterOption) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppFilter.entries.size,
                    ),
                ) {
                    Text(filterOption.label)
                }
            }
        }
    }
}


private fun demoApps(): List<AppInfo> = listOf(
    AppInfo(
        packageName = "com.android.chrome",
        label = "Chrome",
        icon = null,
        uid = 10123,
        versionName = "126.0.6478.71",
        isSystem = false,
        isFrozen = false,
        isInternetBlocked = true,
    ),
    AppInfo(
        packageName = "com.instagram.android",
        label = "Instagram",
        icon = null,
        uid = 10198,
        versionName = "350.1.0.29.108",
        isSystem = false,
        isFrozen = true,
        isInternetBlocked = false,
    ),
    AppInfo(
        packageName = "com.android.systemui",
        label = "System UI",
        icon = null,
        uid = 1000,
        versionName = "15",
        isSystem = true,
        isFrozen = false,
        isInternetBlocked = false,
    ),
)

private fun demoState(): UiState = UiState(
    apps = demoApps(),
    totalApps = 247,
    shizukuReady = true,
    busyPackages = emptySet(),
    error = null,
    firewallOn = true,
)

@Preview(name = "Main screen — light", showBackground = true)
@Composable
private fun AppScreenPreviewLight() {
    FreezerV1Theme {
        AppScreenContent(
            state = demoState(),
            onGrantShizuku = {},
            onFreeze = {},
            onBlock = {},
        )
    }
}

@Preview(
    name = "Main screen — dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AppScreenPreviewDark() {
    FreezerV1Theme {
        AppScreenContent(
            state = demoState(),
            onGrantShizuku = {},
            onFreeze = {},
            onBlock = {},
        )
    }
}

@Preview(
    name = "Shizuku not ready",
    showBackground = true,
    heightDp = 320,
)
@Composable
private fun AppScreenPreviewNotReady() {
    FreezerV1Theme {
        AppScreenContent(
            state = demoState().copy(shizukuReady = false, firewallOn = false, totalApps = 247),
            onGrantShizuku = {},
            onFreeze = {},
            onBlock = {},
        )
    }
}

@Preview(name = "App row", showBackground = true)
@Composable
private fun AppRowPreview() {
    FreezerV1Theme {
        AppRow(
            app = demoApps()[1],
            busy = false,
            shizukuReady = true,
            onFreeze = {},
            onBlock = {},
        )
    }
}
