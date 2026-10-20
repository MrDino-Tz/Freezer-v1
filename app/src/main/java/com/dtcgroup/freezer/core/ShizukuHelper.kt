package com.dtcgroup.freezer.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.dtcgroup.freezer.shizuku.IPmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single integration point with Shizuku:
 *  - tracks binder availability + permission state,
 *  - binds a privileged [PmUserService] once the permission is granted,
 *  - exposes [exec]/[freeze]/[unfreeze]/[forceStop] to the ViewModel.
 *
 * Shell strings are built ONLY from validated package names — no arbitrary command
 * injection is possible from the UI.
 */
object ShizukuHelper {

    const val PERMISSION_REQUEST_CODE = 17

    private val SAFE_PACKAGE_NAME = Regex("[a-zA-Z0-9._]+")

    private var appContext: Context? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private val bindRequested = AtomicBoolean(false)

    private val _userService = MutableStateFlow<IPmService?>(null)
    val userService: StateFlow<IPmService?> = _userService.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.isBinderAlive) {
                _userService.value = IPmService.Stub.asInterface(binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _userService.value = null
        }
    }

    // ---- state ------------------------------------------------------------

    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun isAvailable(): Boolean = Shizuku.pingBinder()

    fun hasPermission(): Boolean {
        if (!isAvailable() || Shizuku.isPreV11()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission() {
        if (isAvailable()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Binds the privileged user service if we haven't yet.
     * Safe to call repeatedly; a no-op without Shizuku + permission.
     */
    fun bindIfNeeded() {
        if (!hasPermission()) return
        if (!bindRequested.compareAndSet(false, true)) return
        val ctx = appContext ?: return
        val args = Shizuku.UserServiceArgs(ComponentName(ctx, PmUserService::class.java))
            .processNameSuffix("pm")
            .daemon(false)
            .version(1)
        serviceArgs = args
        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            bindRequested.set(false)
        }
    }

    // ---- privileged ops ---------------------------------------------------

    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val service = withTimeout(15_000) { _userService.filterNotNull().first() }
            bindIfNeeded()
            val output = service.exec(command)
            val exit = service.lastExitCode()
            if (exit == 0) {
                output
            } else {
                throw IOException("$output (exit code $exit)")
            }
        }
    }

    suspend fun freeze(packageName: String): Result<String> {
        requireValid(packageName)
        return exec("pm disable-user --user 0 $packageName")
    }

    suspend fun unfreeze(packageName: String): Result<String> {
        requireValid(packageName)
        return exec("pm enable $packageName")
    }

    suspend fun forceStop(packageName: String): Result<String> {
        requireValid(packageName)
        return exec("am force-stop $packageName")
    }

    private fun requireValid(packageName: String) {
        require(SAFE_PACKAGE_NAME.matches(packageName)) {
            "Rejected unsafe package name: $packageName"
        }
    }
}
