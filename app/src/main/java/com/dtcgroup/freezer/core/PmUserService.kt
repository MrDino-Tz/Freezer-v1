package com.dtcgroup.freezer.core

import android.annotation.SuppressLint
import androidx.annotation.Keep
import com.dtcgroup.freezer.shizuku.IPmService
import java.lang.Runtime

/**
 * Runs inside the Shizuku server process (uid 2000 shell, or root).
 * The Shizuku server instantiates it via [bindUserService]; all public methods
 * are IPC calls from the main app process.
 *
 * IMPORTANT: the parameterless constructor must exist, and this class implements
 * [IPmService] generated from `IPmService.aidl`. The `destroy() = 16777114`
 * transaction is reserved and invoked by the Shizuku server itself.
 */
@Keep
class PmUserService : IPmService.Stub() {

    private val lock = Any()
    private var lastExit = -1

    override fun exec(command: String): String = synchronized(lock) {
        lastExit = -1
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
            lastExit = process.waitFor()
            if (stderr.isNotBlank()) stdout.trim() + "\n" + stderr.trim() else stdout.trim()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    override fun lastExitCode(): Int = synchronized(lock) { lastExit }

    /** Reserved destroy method — Shizuku calls this to tear the service down. */
    override fun destroy() {
        System.exit(0)
    }
}
