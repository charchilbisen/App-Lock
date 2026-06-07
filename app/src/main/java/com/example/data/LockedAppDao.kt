package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {
    @Query("SELECT * FROM locked_apps")
    fun getAllLockedAppsFlow(): Flow<List<LockedApp>>

    @Query("SELECT * FROM locked_apps")
    suspend fun getAllLockedApps(): List<LockedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLockedApp(app: LockedApp)

    @Delete
    suspend fun deleteLockedApp(app: LockedApp)

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :packageName LIMIT 1)")
    suspend fun isAppLocked(packageName: String): Boolean
}
