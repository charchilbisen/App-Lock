package com.example.viewmodel

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppLockRepository
import com.example.data.LockedApp
import com.example.service.AppLockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val isLocked: Boolean
)

class AppLockViewModel(private val context: Context) : ViewModel() {

    private val repository = AppLockRepository.getInstance(context)
    private val pm: PackageManager = context.packageManager

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    
    // Combine installed apps with search query and Room db to get final displayed list
    val uiAppState: StateFlow<List<AppItem>> = combine(
        _installedApps,
        _searchQuery,
        repository.lockedAppsFlow
    ) { apps, query, lockedApps ->
        val lockedPackageNames = lockedApps.map { it.packageName }.toSet()
        val mappedList = apps.map { app ->
            app.copy(isLocked = lockedPackageNames.contains(app.packageName))
        }
        if (query.isBlank()) {
            mappedList
        } else {
            mappedList.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isServiceActive = MutableStateFlow(repository.isServiceActive())
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(repository.isBiometricEnabled())
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled.asStateFlow()

    private val _hasUsageStatsPermission = MutableStateFlow(checkUsageStatsPermission())
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()

    private val _hasOverlayPermission = MutableStateFlow(checkOverlayPermission())
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Main App lock authentication gating state
    private val _dashboardUnlocked = MutableStateFlow(!repository.isPinSet())
    val dashboardUnlocked: StateFlow<Boolean> = _dashboardUnlocked.asStateFlow()

    private val _isPinSetState = MutableStateFlow(repository.isPinSet())
    val isPinSetState: StateFlow<Boolean> = _isPinSetState.asStateFlow()

    private val _themeMode = MutableStateFlow(repository.getThemeMode())
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _lockDelaySeconds = MutableStateFlow(repository.getLockDelaySeconds())
    val lockDelaySeconds: StateFlow<Int> = _lockDelaySeconds.asStateFlow()

    private val _isTouchSoundEnabled = MutableStateFlow(repository.isTouchSoundEnabled())
    val isTouchSoundEnabled: StateFlow<Boolean> = _isTouchSoundEnabled.asStateFlow()

    private val _securityQuestion = MutableStateFlow(repository.getSecurityQuestion())
    val securityQuestion: StateFlow<String?> = _securityQuestion.asStateFlow()

    private val _securityAnswer = MutableStateFlow(repository.getSecurityAnswer())
    val securityAnswer: StateFlow<String?> = _securityAnswer.asStateFlow()

    fun setThemeMode(mode: String) {
        repository.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setLockDelaySeconds(seconds: Int) {
        repository.setLockDelaySeconds(seconds)
        _lockDelaySeconds.value = seconds
    }

    fun saveSecurityQuestionAndAnswer(question: String, answer: String) {
        repository.saveSecurityQuestion(question)
        repository.saveSecurityAnswer(answer)
        _securityQuestion.value = question
        _securityAnswer.value = answer
    }

    fun isSecurityQuestionSet(): Boolean {
        return repository.isSecurityQuestionSet()
    }

    init {
        refreshAppsList()
    }

    fun markDashboardUnlocked(unlocked: Boolean) {
        _dashboardUnlocked.value = unlocked
    }

    fun isPinSet(): Boolean {
        return repository.isPinSet()
    }

    fun getSavedPin(): String {
        return repository.getSavedPin() ?: ""
    }

    fun savePin(pin: String) {
        repository.savePin(pin)
        _isPinSetState.value = true
        _dashboardUnlocked.value = true
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refreshAppsList() {
        viewModelScope.launch {
            _isLoading.value = true
            val apps = withContext(Dispatchers.IO) {
                loadInstalledLaunchableApps()
            }
            _installedApps.value = apps
            _isLoading.value = false
        }
    }

    fun updatePermissionsStatus() {
        _hasUsageStatsPermission.value = checkUsageStatsPermission()
        _hasOverlayPermission.value = checkOverlayPermission()
    }

    fun toggleAppLock(packageName: String, appName: String, lock: Boolean) {
        viewModelScope.launch {
            if (lock) {
                repository.lockApp(packageName, appName)
            } else {
                repository.unlockApp(packageName)
            }
        }
    }

    fun toggleServiceActive(active: Boolean) {
        repository.setServiceActive(active)
        _isServiceActive.value = active
        if (active) {
            startLockerService()
        } else {
            stopLockerService()
        }
    }

    fun toggleBiometricEnabled(enabled: Boolean) {
        repository.setBiometricEnabled(enabled)
        _isBiometricEnabled.value = enabled
    }

    fun toggleTouchSoundEnabled(enabled: Boolean) {
        repository.setTouchSoundEnabled(enabled)
        _isTouchSoundEnabled.value = enabled
    }

    private fun loadInstalledLaunchableApps(): List<AppItem> {
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
        return resolveInfos.mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            // Exclude our own app so we don't list ourselves
            if (appInfo.packageName == context.packageName) {
                null
            } else {
                val appLabel = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                AppItem(
                    packageName = appInfo.packageName,
                    appName = appLabel,
                    icon = icon,
                    isLocked = false
                )
            }
        }.distinctBy { it.packageName }
            .sortedBy { it.appName }
    }

    private fun startLockerService() {
        val intent = Intent(context, AppLockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopLockerService() {
        val intent = Intent(context, AppLockService::class.java)
        context.stopService(intent)
    }

    fun checkUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppLockViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AppLockViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
