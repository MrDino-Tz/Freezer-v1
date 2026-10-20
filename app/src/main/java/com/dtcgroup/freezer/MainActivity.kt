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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.dtcgroup.freezer.ui.AppControlViewModel
import com.dtcgroup.freezer.ui.UiState
import com.dtcgroup.freezer.ui.theme.FreezerV1Theme
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

@Composable
fun AppScreen(viewModel: AppControlViewModel) {
    val state by viewModel.state.collectAsState()

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
        onErrorShown = viewModel::errorShown,
    )
}

@Composable
fun AppScreenContent(
    state: UiState,
    onGrantShizuku: () -> Unit,
    onFreeze: (AppInfo) -> Unit,
    onBlock: (AppInfo) -> Unit,
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

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredApps, key = { it.uid to it.packageName }) { app ->
                    AppRow(
                        app = app,
                        busy = app.packageName in state.busyPackages,
                        shizukuReady = state.shizukuReady,
                        onFreeze = { onFreeze(app) },
                        onBlock = { onBlock(app) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(
    totalApps: Int,
    shizukuReady: Boolean,
    firewallOn: Boolean,
    onGrantShizuku: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "Total apps: $totalApps",
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
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
