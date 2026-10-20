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
import com.dtcgroup.freezer.data.AppInfo
import com.dtcgroup.freezer.data.BlockedAppDao
import com.dtcgroup.freezer.data.BlockedAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/**
 * Single source of truth. All privileged mutations (freeze/unfreeze, block/unblock)
 * flow through here; the UI never touches platform APIs directly.
 */
class AppControlViewModel(private val app: Application) : AndroidViewModel(app) {

    private val dao: BlockedAppDao = AppDatabase.get(app).blockedAppDao()
    private val scanner = AppScanner(app)

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
            _state.update { it.copy(shizukuReady = ShizukuHelper.hasPermission()) }
            ShizukuHelper.bindIfNeeded()
            val blocked = withContext(Dispatchers.IO) {
                dao.getAll().map { it.packageName }.toSet()
            }
            val apps = scanner.scanAll(blocked)
            _state.update {
                it.copy(
                    apps = apps,
                    totalApps = apps.size,
                    firewallOn = FirewallVpnService.isRunning(),
                )
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
}
