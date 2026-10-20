package com.dtcgroup.freezer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row = one app with its internet blocked by the VPN tunnel.
 */
@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey val packageName: String,
)
