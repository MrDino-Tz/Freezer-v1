package com.dtcgroup.freezer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockedAppDao {

    @Query("SELECT * FROM blocked_apps")
    suspend fun getAll(): List<BlockedAppEntity>

    /** Blocking variant, used by [com.dtcgroup.freezer.core.FirewallVpnService] when the system restarts it. */
    @Query("SELECT * FROM blocked_apps")
    fun getAllBlocking(): List<BlockedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockedAppEntity)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT COUNT(*) FROM blocked_apps")
    fun countBlocking(): Int
}
