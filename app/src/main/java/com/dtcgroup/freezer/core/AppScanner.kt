package com.dtcgroup.freezer.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.dtcgroup.freezer.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Enumerates every installed app and derives the current freeze / block state.
 * Always re-derives from PackageManager so external changes (adb, vendor ROM,
 * user re-install) are reflected immediately.
 */
class AppScanner(private val context: Context) {

    suspend fun scanAll(blocked: Set<String>): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val self = context.packageName
        val frozenStates = setOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        )

        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != self } // never list ourselves
            .map { ai ->
                val enabledSetting = pm.getApplicationEnabledSetting(ai.packageName)
                val versionName = runCatching {
                    pm.getPackageInfo(ai.packageName, 0).versionName
                }.getOrNull()
                AppInfo(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm).toString(),
                    icon = ai.loadIcon(pm),
                    uid = ai.uid,
                    versionName = versionName,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isFrozen = enabledSetting in frozenStates || !ai.enabled,
                    isInternetBlocked = ai.packageName in blocked,
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase(Locale.ROOT) }))
    }
}
