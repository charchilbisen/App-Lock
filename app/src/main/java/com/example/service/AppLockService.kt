package com.example.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppLockRepository
import com.example.ui.LockActivity
import kotlinx.coroutines.*

class AppLockService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var repository: AppLockRepository
    private var isRunning = false

    private val NOTIFICATION_ID = 20260607
    private val CHANNEL_ID = "AppLockServiceChannel"

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d("AppLockService", "Screen turned off. Clearing all unlocked apps cache with delay handler.")
                repository.clearTemporaryUnlockCacheOnScreenOff()
                lastActiveUserApp = null
            }
        }
    }

    private var lastActiveUserApp: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = AppLockRepository.getInstance(this)
        createNotificationChannel()

        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("AppLockService", "Failed to register screen off receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startForegroundServiceWithNotification()
            startAppMonitoringLoop()
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Lock Active")
            .setContentText("Persistent app protection is running in the background.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("AppLockService", "Failed to start foreground service: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "App Lock Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors app usage in backgournd for app locking features"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startAppMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    // Poll current foreground package
                    val foregroundPackage = getForegroundApp(this@AppLockService)
                    if (foregroundPackage != null) {
                        // Check if foreground package is ignored (launcher, system UI, our app)
                        val isIgnored = foregroundPackage == packageName ||
                                foregroundPackage == "com.android.systemui" ||
                                foregroundPackage == "android" ||
                                foregroundPackage.contains("launcher") ||
                                foregroundPackage == "com.google.android.googlequicksearchbox"

                        if (isIgnored) {
                            // If the user went to launcher, lock screen, or system UI helpers, they exited the app
                            if (lastActiveUserApp != null) {
                                Log.d("AppLockService", "Exited to ignored package ($foregroundPackage). Revoking unlock for $lastActiveUserApp.")
                                repository.revokeTemporaryUnlock(lastActiveUserApp!!)
                                lastActiveUserApp = null
                            }
                        } else {
                            // If the user transitioned to a different user application, we lock the previous one
                            if (lastActiveUserApp != null && lastActiveUserApp != foregroundPackage) {
                                Log.d("AppLockService", "Switched from $lastActiveUserApp to $foregroundPackage. Revoking unlock for $lastActiveUserApp.")
                                repository.revokeTemporaryUnlock(lastActiveUserApp!!)
                            }
                            lastActiveUserApp = foregroundPackage
                        }

                        handleForegroundApp(foregroundPackage)
                    }
                } catch (e: Exception) {
                    Log.e("AppLockService", "Error in app monitoring loop", e)
                }
                delay(400) // Lower delay for responsiveness, but optimized for battery (400ms is standard)
            }
        }
    }

    private suspend fun handleForegroundApp(packageName: String) {
        // Essential Guard: Never lock our own application, launcher UI, or standard system components
        val isOurApp = packageName == this.packageName
        val isSystemIgnored = packageName == "com.android.systemui" ||
                packageName == "android" ||
                packageName.contains("launcher") ||
                packageName == "com.google.android.googlequicksearchbox"

        if (isOurApp || isSystemIgnored) {
            return
        }

        // Uninstallation & Clearing Protection: Lock Settings and Package Installers so they require PIN
        val isProtectedUninstallApp = packageName == "com.android.settings" ||
                packageName == "com.android.packageinstaller" ||
                packageName == "com.google.android.packageinstaller" ||
                packageName == "com.sec.android.app.packageinstaller"

        val shouldLock = repository.isServiceActive() && (isProtectedUninstallApp || repository.isAppLocked(packageName))

        if (shouldLock) {
            // Check if this app has already been authorized or temporarily unlocked
            if (!repository.isTemporarilyUnlocked(packageName)) {
                // If not unlocked, start LockActivity overlay screen
                launchLockScreen(packageName)
            }
        }
    }

    private fun launchLockScreen(packageName: String) {
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            putExtra("target_package", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(lockIntent)
    }

    private fun getForegroundApp(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        // Query last 10 seconds
        val usageEvents = usageStatsManager.queryEvents(time - 10000, time)
        val event = UsageEvents.Event()
        var foregroundApp: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundApp = event.packageName
            }
        }

        if (foregroundApp == null) {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time)
            if (!stats.isNullOrEmpty()) {
                foregroundApp = stats.maxByOrNull { it.lastTimeUsed }?.packageName
            }
        }
        return foregroundApp
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            Log.e("AppLockService", "Failed to unregister screen off receiver", e)
        }
        serviceJob.cancel()
        super.onDestroy()
    }
}
