package com.dtcgroup.freezer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.dtcgroup.freezer.MainActivity
import com.dtcgroup.freezer.R
import com.dtcgroup.freezer.data.AppDatabase
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Local VPN tunnel used as a per-app internet kill switch.
 *
 * All traffic is routed into the tunnel (0.0.0.0/0 + ::/0). Every app that is
 * NOT blocked is added via addDisallowedApplication(uid) so it bypasses the VPN.
 * Packets drained from the tunnel are read and deliberately DISCARDED — blocked
 * apps therefore never reach the network, while all other apps are untouched.
 */
// (min SDK bumped to 29 because VpnService.Builder.addDisallowedApplication(String)
// is API 29+, which is how we do per-app blocking.)
class FirewallVpnService : android.net.VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val draining = AtomicBoolean(false)
    private var blockedPackages: Set<String> = emptySet()

    // ---- public control surface -------------------------------------------

    companion object {
        private const val TAG = "FirewallVpnService"
        private const val CHANNEL_ID = "firewall_vpn"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.dtcgroup.freezer.vpn.action.START"
        const val ACTION_UPDATE = "com.dtcgroup.freezer.vpn.action.UPDATE"
        const val ACTION_STOP = "com.dtcgroup.freezer.vpn.action.STOP"
        const val EXTRA_BLOCKED = "com.dtcgroup.freezer.vpn.extra.BLOCKED"

        @Volatile
        private var alive = false

        fun isRunning(): Boolean = alive

        private fun intentFor(context: Context, action: String, blocked: Set<String>?): Intent =
            Intent(context, FirewallVpnService::class.java)
                .setAction(action)
                .apply {
                    if (blocked != null) {
                        putStringArrayListExtra(EXTRA_BLOCKED, ArrayList(blocked))
                    }
                }

        fun start(context: Context, blocked: Set<String>) {
            context.startForegroundService(intentFor(context, ACTION_START, blocked))
        }

        fun update(context: Context, blocked: Set<String>) {
            // The service is already foregrounded; a plain startService avoids the
            // startForegroundService() obligation on the UPDATE path.
            context.startService(intentFor(context, ACTION_UPDATE, blocked))
        }

        fun stop(context: Context) {
            context.startService(intentFor(context, ACTION_STOP, null))
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                teardown()
                alive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE, null -> {
                // action == null means START_STICKY-style restart; we use NOT_STICKY but
                // still handle it defensively by reconciling from the database.
                val blocked = intent?.getStringArrayListExtra(EXTRA_BLOCKED)?.toSet()
                    ?: lastBlockedFromDb()
                if (blocked.isEmpty()) {
                    teardown()
                    alive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                blockedPackages = blocked
                makeForeground()
                val ok = buildTunnel(blocked)
                alive = ok
                if (!ok) {
                    Log.e(TAG, "tunnel failed to establish (another VPN active or consent revoked)")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        alive = false
        super.onDestroy()
    }

    private fun lastBlockedFromDb(): Set<String> = runBlocking {
        AppDatabase.get(applicationContext).blockedAppDao().getAllBlocking()
            .map { it.packageName }
            .toSet()
    }

    // ---- foreground notification ------------------------------------------

    private fun makeForeground() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "App firewall active",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("App Freezer firewall")
            .setContentText("Internet blocked for ${blockedPackages.size} app(s)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    // ---- the tunnel --------------------------------------------------------

    private fun buildTunnel(blocked: Set<String>): Boolean {
        teardown()

        val pm = packageManager
        val installed = pm.getInstalledApplications(0)

        val builder = Builder()
            .setSession("App Freezer Firewall")
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00::1", 128)
            .addRoute("::", 0)
            .setBlocking(true)
            .setMtu(1500)

        // Every app that is not blocked bypasses the tunnel and keeps normal connectivity.
        for (ai in installed) {
            val pkg = ai.packageName
            if (pkg == packageName || pkg in blocked) continue
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "could not disallow $pkg: ${e.message}")
            }
        }

        return try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                false
            } else {
                startDrain()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "establish() threw", e)
            vpnInterface = null
            false
        }
    }

    /**
     * Reads every packet that arrives on the TUN interface and DISCARDS it.
     * Discard = no forwarding = no internet for the blocked app; its TCP
     * retransmits until it gives up.
     */
    private fun startDrain() {
        draining.set(true)
        val pfd = vpnInterface ?: return
        thread(name = "vpn-drain", isDaemon = true) {
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buffer = ByteArray(4096)
                    while (draining.get()) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        // intentionally discarded
                    }
                }
            } catch (_: IOException) {
                // interface closed
            }
        }
    }

    private fun teardown() {
        draining.set(false)
        val pfd = vpnInterface
        vpnInterface = null
        try {
            pfd?.close()
        } catch (_: IOException) {
        }
    }
}
