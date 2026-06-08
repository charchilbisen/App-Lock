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
    private val minimizedAppsCache = ConcurrentHashMap<String, Long>()

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

    // Security Question management
    fun getSecurityQuestion(): String? {
        return prefs.getString("security_question", null)
    }

    fun saveSecurityQuestion(question: String) {
        prefs.edit().putString("security_question", question).apply()
    }

    fun getSecurityAnswer(): String? {
        return prefs.getString("security_answer", null)
    }

    fun saveSecurityAnswer(answer: String) {
        prefs.edit().putString("security_answer", answer).apply()
    }

    fun isSecurityQuestionSet(): Boolean {
        return getSecurityQuestion() != null && getSecurityAnswer() != null
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

    // Configurable Lock Delay settings in seconds
    fun getLockDelaySeconds(): Int {
        return prefs.getInt("lock_delay_seconds", 0)
    }

    fun setLockDelaySeconds(seconds: Int) {
        prefs.edit().putInt("lock_delay_seconds", seconds).apply()
    }

    // Temporary unlock mechanism for user convenience so loops don't lock them.
    // Kept unlocked until they exit the app or lock the screen.
    fun isTemporarilyUnlocked(packageName: String): Boolean {
        val unlockedAt = unlockedAppsCache[packageName] ?: return false
        val delaySec = getLockDelaySeconds()
        if (delaySec <= 0) {
            return true
        }
        val minimizeTime = minimizedAppsCache[packageName] ?: return true
        val elapsed = System.currentTimeMillis() - minimizeTime
        if (elapsed < delaySec * 1000L) {
            minimizedAppsCache.remove(packageName)
            return true
        } else {
            unlockedAppsCache.remove(packageName)
            minimizedAppsCache.remove(packageName)
            return false
        }
    }

    fun markTemporarilyUnlocked(packageName: String, durationMillis: Long = 15000) {
        unlockedAppsCache[packageName] = System.currentTimeMillis()
        minimizedAppsCache.remove(packageName)
    }

    fun revokeTemporaryUnlock(packageName: String) {
        val delaySec = getLockDelaySeconds()
        if (delaySec <= 0) {
            unlockedAppsCache.remove(packageName)
            minimizedAppsCache.remove(packageName)
        } else {
            minimizedAppsCache[packageName] = System.currentTimeMillis()
        }
    }

    fun clearTemporaryUnlockCache() {
        unlockedAppsCache.clear()
        minimizedAppsCache.clear()
    }

    fun clearTemporaryUnlockCacheOnScreenOff() {
        val delaySec = getLockDelaySeconds()
        if (delaySec <= 0) {
            unlockedAppsCache.clear()
            minimizedAppsCache.clear()
        } else {
            val now = System.currentTimeMillis()
            unlockedAppsCache.keys.forEach { packageName ->
                minimizedAppsCache.putIfAbsent(packageName, now)
            }
        }
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
