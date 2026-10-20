package com.dtcgroup.freezer.data

import android.graphics.drawable.Drawable

/**
 * In-memory DTO describing one installed app row.
 * Frozen state is always derived from PackageManager at scan time — never stored.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val uid: Int,
    val versionName: String?,
    val isSystem: Boolean,
    val isFrozen: Boolean,
    val isInternetBlocked: Boolean,
)
