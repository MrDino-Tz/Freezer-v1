package com.dtcgroup.freezer.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtcgroup.freezer.core.AppScanner
import com.dtcgroup.freezer.core.FirewallVpnService
import com.dtcgroup.freezer.core.ShizukuHelper
import com.dtcgroup.freezer.data.AppDatabase
import com.dtcgroup.freezer.data.AppDetailsLoader
import com.dtcgroup.freezer.data.AppExtras
import com.dtcgroup.freezer.data.AppInfo
import com.dtcgroup.freezer.data.BlockedAppDao
import com.dtcgroup.freezer.data.BlockedAppEntity
import com.dtcgroup.freezer.data.ProcessSummary
import com.dtcgroup.freezer.data.UsageSnapshot
import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val apps: List<AppInfo> = emptyList(),
    val totalApps: Int = 0,
    val shizukuReady: Boolean = false,
    val busyPackages: Set<String> = emptySet(),
    val error: String? = null,
    val firewallOn: Boolean = false,
    val isScanning: Boolean = false,
)

/**
 * Single source of truth. All privileged mutations (freeze/unfreeze, block/unblock)
 * flow through here; the UI never touches platform APIs directly.
 */
class AppControlViewModel(private val app: Application) : AndroidViewModel(app) {

    private val dao: BlockedAppDao = AppDatabase.get(app).blockedAppDao()
    private val scanner = AppScanner(app)
    private val detailsLoader = AppDetailsLoader(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _vpnConsent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val vpnConsent: SharedFlow<Intent> = _vpnConsent.asSharedFlow()

    /** App inserted while waiting for the VPN consent dialog — reverted if consent denied. */
    private var pendingAdd: String? = null

    init {
        refresh()
    }

    // ---- scan --------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(shizukuReady = ShizukuHelper.hasPermission(), isScanning = true) }
            ShizukuHelper.bindIfNeeded()
            try {
                val blocked = withContext(Dispatchers.IO) {
                    dao.getAll().map { it.packageName }.toSet()
                }
                val apps = scanner.scanAll(blocked)
                _state.update {
                    it.copy(
                        apps = apps,
                        totalApps = apps.size,
                        firewallOn = FirewallVpnService.isRunning(),
                        isScanning = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isScanning = false,
                        error = "Scan failed: ${t.message}",
                    )
                }
            }
        }
    }

    /** Lightweight re-check of the Shizuku binder/permission state (listeners call this). */
    fun updateShizukuState() {
        _state.update { it.copy(shizukuReady = ShizukuHelper.hasPermission()) }
        ShizukuHelper.bindIfNeeded()
    }

    fun onShizukuPermissionResult(granted: Boolean) {
        if (granted) {
            ShizukuHelper.bindIfNeeded()
        }
        updateShizukuState()
    }

    fun requestShizukuPermission() {
        ShizukuHelper.requestPermission()
    }

    fun errorShown() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Shared runner for row-level privileged actions (force stop, uninstall):
     * guards Shizuku permission, marks the row busy, refreshes on completion.
     */
    private fun runPrivileged(
        app: AppInfo,
        errorLabel: String,
        operation: suspend (String) -> Result<String>,
    ) {
        if (!ShizukuHelper.hasPermission()) {
            _state.update { it.copy(error = "Grant the Shizuku permission first") }
            return
        }
        _state.update { it.copy(busyPackages = it.busyPackages + app.packageName) }
        viewModelScope.launch {
            operation(app.packageName).fold(
                onSuccess = { refresh() },
                onFailure = { e ->
                    _state.update { it.copy(error = "$errorLabel: ${e.message}") }
                    refresh()
                },
            )
            _state.update { it.copy(busyPackages = it.busyPackages - app.packageName) }
        }
    }

    /** Kills the app's running instance (`am force-stop`) so its notifications etc. stop. */
    fun forceStop(app: AppInfo) =
        runPrivileged(app, "Force stop failed", ShizukuHelper::forceStop)

    /** Removes the app for user 0 without root (`pm uninstall --user 0`). */
    fun uninstallForCurrentUser(app: AppInfo) =
        runPrivileged(app, "Uninstall failed", ::pmUninstallForCurrentUser)

    private suspend fun pmUninstallForCurrentUser(pkg: String): Result<String> =
        ShizukuHelper.exec("pm uninstall --user 0 $pkg")

    // ---- freeze / unfreeze (Shizuku) ---------------------------------------

    fun toggleFreeze(app: AppInfo) {
        if (!ShizukuHelper.hasPermission()) {
            _state.update { it.copy(error = "Grant the Shizuku permission first") }
            return
        }
        // Mark busy so the row's switch is disabled while in flight.
        _state.update { it.copy(busyPackages = it.busyPackages + app.packageName) }

        viewModelScope.launch {
            val result = if (app.isFrozen) {
                ShizukuHelper.unfreeze(app.packageName)
            } else {
                ShizukuHelper.freeze(app.packageName)
            }
            result.fold(
                onSuccess = { refresh() },
                onFailure = { e ->
                    _state.update { it.copy(error = "Freeze failed: ${e.message}") }
                    refresh()
                },
            )
            _state.update { it.copy(busyPackages = it.busyPackages - app.packageName) }
        }
    }

    // ---- internet block (DB + VPN) ------------------------------------------

    fun toggleInternetBlock(app: AppInfo) {
        val wasBlocked = app.isInternetBlocked
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (wasBlocked) {
                    dao.delete(app.packageName)
                } else {
                    dao.insert(BlockedAppEntity(app.packageName))
                }
            }
            // Optimistic row update; refresh() reconciles on next pass.
            _state.update { s ->
                s.copy(
                    apps = s.apps.map {
                        if (it.packageName == app.packageName) {
                            it.copy(isInternetBlocked = !wasBlocked)
                        } else {
                            it
                        }
                    }
                )
            }
            val newBlocked = withContext(Dispatchers.IO) {
                dao.getAll().map { it.packageName }.toSet()
            }
            if (newBlocked.isEmpty()) {
                FirewallVpnService.stop(getApplication())
                _state.update { it.copy(firewallOn = false) }
                return@launch
            }
            val consent = VpnService.prepare(getApplication())
            if (consent != null) {
                pendingAdd = if (wasBlocked) null else app.packageName
                _vpnConsent.tryEmit(consent)
            } else {
                applyVpnState(newBlocked)
            }
        }
    }

    private fun applyVpnState(blocked: Set<String>) {
        val context = getApplication<Application>()
        if (FirewallVpnService.isRunning()) {
            FirewallVpnService.update(context, blocked)
        } else {
            FirewallVpnService.start(context, blocked)
        }
        _state.update { it.copy(firewallOn = true) }
    }

    /** Called by the activity's activity-result launcher for the VPN consent dialog. */
    fun onVpnConsentResult(granted: Boolean) {
        val wasAdd = pendingAdd
        pendingAdd = null
        viewModelScope.launch {
            val blocked = withContext(Dispatchers.IO) {
                if (!granted) {
                    if (wasAdd != null) {
                        dao.delete(wasAdd)
                    }
                    dao.getAll().map { it.packageName }.toSet()
                } else {
                    dao.getAll().map { it.packageName }.toSet()
                }
            }
            if (granted) {
                applyVpnState(blocked)
            } else {
                _state.update { it.copy(error = "VPN consent denied — nothing blocked") }
            }
            refresh()
        }
    }

    // ---- details sheet -------------------------------------------------------

    /** One-shot extended details for the details sheet. */
    suspend fun loadDetails(packageName: String): AppExtras? =
        detailsLoader.load(packageName)

    /**
     * Real-time process list for an app, observed via Shizuku (`ps -A -o PID,UID,NAME`).
     * Emits every second while collected; cancels automatically when the sheet closes.
     * Note: getRunningAppProcesses() is restricted to our own package on Android 10+,
     * which is why the privileged `ps` snapshot is used instead.
     */
    fun processUpdates(uid: Int): Flow<List<ProcessSummary>> = flow {
        while (true) {
            emit(sampleProcesses(uid))
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Live internet usage (cumulative since boot + per-second rate) for an app's uid,
     * sampled from TrafficStats every second while collected.
     */
    fun usageUpdates(uid: Int): Flow<UsageSnapshot> = flow {
        var previous: UsageSnapshot? = null
        while (true) {
            val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            val snapshot = UsageSnapshot(
                rxBytes = rx,
                txBytes = tx,
                rxSpeedBytes = previous?.let { (rx - it.rxBytes).coerceAtLeast(0) } ?: 0L,
                txSpeedBytes = previous?.let { (tx - it.txBytes).coerceAtLeast(0) } ?: 0L,
            )
            emit(snapshot)
            previous = snapshot
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun sampleProcesses(uid: Int): List<ProcessSummary> {
        val output = ShizukuHelper.exec("ps -A -o PID,UID,NAME").getOrNull() ?: return emptyList()
        return output.lines()
            .mapNotNull { line ->
                val tokens = line.trim().split("\\s+".toRegex())
                if (tokens.size < 3 || tokens[0] == "PID") return@mapNotNull null
                val pid = tokens[0].toIntOrNull() ?: return@mapNotNull null
                val lineUid = tokens[1].toIntOrNull() ?: return@mapNotNull null
                if (lineUid != uid) return@mapNotNull null
                ProcessSummary(pid = pid, processName = tokens.drop(2).joinToString(" "))
            }
    }
}
