package com.dtcgroup.freezer.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extended details loaded lazily when the details sheet opens. */
data class AppExtras(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val permissions: List<PermissionEntry>,
)

/** One requested permission with its runtime grant state. */
data class PermissionEntry(
    val fullName: String,
    val granted: Boolean,
) {
    val shortName: String
        get() = fullName.substringAfterLast('.')
}

/** One live process observed for an app's uid. */
data class ProcessSummary(
    val pid: Int,
    val processName: String,
)

/** Internet usage for an app's uid, sampled live from TrafficStats. */
data class UsageSnapshot(
    val rxBytes: Long,
    val txBytes: Long,
    /** Per-second deltas since the previous sample (never negative). */
    val rxSpeedBytes: Long,
    val txSpeedBytes: Long,
) 

/**
 * Loads extended per-app metadata (version, SDK levels, install times,
 * requested permissions + grant state).
 */
class AppDetailsLoader(private val context: Context) {

    suspend fun load(packageName: String): AppExtras? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packageInfo = runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return@withContext null

        val appInfo = packageInfo.applicationInfo
        val requested = packageInfo.requestedPermissions
        val grantedFlags = packageInfo.requestedPermissionsFlags

        val permissions = requested?.mapIndexed { index, fullName ->
            val granted = grantedFlags != null &&
                index < grantedFlags.size &&
                (grantedFlags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            PermissionEntry(fullName = fullName, granted = granted)
        }?.orEmpty() ?: emptyList()

        AppExtras(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName,
            versionCode = runCatching { packageInfo.longVersionCode }.getOrDefault(0L),
            minSdk = appInfo?.minSdkVersion ?: 0,
            targetSdk = appInfo?.targetSdkVersion ?: 0,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            permissions = permissions.sortedWith(compareBy({ !it.granted }, { it.fullName })),
        )
    }
}
