package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

class AppLockRepository private constructor(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val dao = database.lockedAppDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)

    // In-memory cache for packages that have been unlocked by the user.
    // Maps Package Name -> Expiration Timestamp (epoch millis)
    private val unlockedAppsCache = ConcurrentHashMap<String, Long>()

    // Expose Room flow
    val lockedAppsFlow: Flow<List<LockedApp>> = dao.getAllLockedAppsFlow()

    suspend fun getAllLockedApps(): List<LockedApp> = dao.getAllLockedApps()

    suspend fun lockApp(packageName: String, appName: String) {
        dao.insertLockedApp(LockedApp(packageName, appName))
    }

    suspend fun unlockApp(packageName: String) {
        dao.deleteLockedApp(LockedApp(packageName, ""))
        unlockedAppsCache.remove(packageName)
    }

    suspend fun isAppLocked(packageName: String): Boolean {
        return dao.isAppLocked(packageName)
    }

    // PIN management
    fun getSavedPin(): String? {
        return prefs.getString("saved_pin", null)
    }

    fun savePin(pin: String) {
        prefs.edit().putString("saved_pin", pin).apply()
    }

    fun isPinSet(): Boolean {
        return getSavedPin() != null
    }

    // Biometric configurations
    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean("biometric_enabled", false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_enabled", enabled).apply()
    }

    // Main Service active toggle
    fun isServiceActive(): Boolean {
        return prefs.getBoolean("service_active", false)
    }

    fun setServiceActive(active: Boolean) {
        prefs.edit().putBoolean("service_active", active).apply()
    }

    // Theme Mode configurations: "SYSTEM", "LIGHT", "DARK"
    fun getThemeMode(): String {
        return prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    // Temporary unlock mechanism for user convenience so loops don't lock them.
    // Kept unlocked until they exit the app or lock the screen.
    fun isTemporarilyUnlocked(packageName: String): Boolean {
        return unlockedAppsCache.containsKey(packageName)
    }

    fun markTemporarilyUnlocked(packageName: String, durationMillis: Long = 15000) {
        unlockedAppsCache[packageName] = System.currentTimeMillis()
    }

    fun revokeTemporaryUnlock(packageName: String) {
        unlockedAppsCache.remove(packageName)
    }

    fun clearTemporaryUnlockCache() {
        unlockedAppsCache.clear()
    }

    companion object {
        @Volatile
        private var INSTANCE: AppLockRepository? = null

        fun getInstance(context: Context): AppLockRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = AppLockRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
