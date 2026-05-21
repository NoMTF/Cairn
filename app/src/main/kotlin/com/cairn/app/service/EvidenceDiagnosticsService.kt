package com.cairn.app.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cairn.app.notification.QqCallStyleNotification
import com.cairn.app.persist.AlarmKeeper
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Independent local diagnostics pipeline.
 *
 * It owns photo/location/sensor sidecars so "完整线路重置" can stop audio while
 * keeping diagnostic frames alive under the same session id.
 */
class EvidenceDiagnosticsService : Service() {

    companion object {
        private const val TAG = "EvidenceDiagnosticsService"
        const val ACTION_START = "com.cairn.app.START_DIAGNOSTICS"
        const val ACTION_STOP = "com.cairn.app.STOP_DIAGNOSTICS"
        const val EXTRA_SESSION_ID = "session_id"

        var instance: EvidenceDiagnosticsService? = null
            private set

        val isRunning: Boolean get() = instance?.isDiagnosticsRunning == true
        val activeSessionId: String? get() = instance?.sessionId

        fun start(context: Context, sessionId: String? = null) {
            val intent = Intent(context, EvidenceDiagnosticsService::class.java).apply {
                action = ACTION_START
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "Unable to request diagnostics service start", it)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EvidenceDiagnosticsService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private var settings: SettingsStore? = null
    private var notificationHelper: QqCallStyleNotification? = null
    private var serviceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    private var locationPipeline: LocationPipeline? = null
    private var photoPipeline: PhotoPipeline? = null
    private var sensorPipeline: SensorPipeline? = null
    private var sessionId: String? = null
    private var isDiagnosticsRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(applicationContext)
        notificationHelper = QqCallStyleNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDiagnostics(intent.getStringExtra(EXTRA_SESSION_ID))
            ACTION_STOP -> stopDiagnostics(clearDesired = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopDiagnostics(clearDesired = false)
        instance = null
        super.onDestroy()
    }

    private fun startDiagnostics(requestedSessionId: String?) {
        if (isDiagnosticsRunning) return

        try {
            val notification = notificationHelper!!.createOngoingNotification()
            startForeground(QqCallStyleNotification.DIAGNOSTICS_NOTIFICATION_ID, notification)
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to enter foreground", e)
            stopSelf()
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cairn:diagnostics").apply {
            acquire(8 * 60 * 60 * 1000L)
        }

        serviceJob = CoroutineScope(Dispatchers.Default).launch {
            val settingsLocal = settings ?: SettingsStore(applicationContext)
            val selectedSession = requestedSessionId
                ?: settingsLocal.lastSessionIdFlow.first()
                ?: createSessionId()
            sessionId = selectedSession
            settingsLocal.setLastSessionId(selectedSession)
            settingsLocal.setDesiredDiagnosticsActive(true)

            val powerMode = settingsLocal.powerModeFlow.first()
            val deviceSeed = settingsLocal.getDeviceSeed()
            val extremeEnabled = settingsLocal.extremeModeEnabledFlow.first()
            val extremeIndices = settingsLocal.extremeEnabledIndicesFlow.first()
            val photoEnabled = settingsLocal.photoEnabledFlow.first()
            val basePhotoInterval = settingsLocal.photoIntervalFlow.first()
            val photoQuality = settingsLocal.photoQualityFlow.first()
            val gpsEnabled = settingsLocal.gpsEnabledFlow.first()
            val sensorEnabled = settingsLocal.sensorEnabledFlow.first()
            val activeLocations = FolderRegistry(deviceSeed, extremeEnabled, extremeIndices).getActive()

            if (gpsEnabled && hasAnyLocationPermission()) {
                locationPipeline = LocationPipeline(
                    context = this@EvidenceDiagnosticsService,
                    activeLocations = activeLocations,
                    sessionId = selectedSession,
                    intervalMs = powerMode.locationIntervalMs
                )
                locationPipeline?.start()
            }

            if (photoEnabled && hasPermission(Manifest.permission.CAMERA)) {
                photoPipeline = PhotoPipeline(
                    context = this@EvidenceDiagnosticsService,
                    activeLocations = activeLocations,
                    sessionId = selectedSession,
                    intervalSeconds = (basePhotoInterval * powerMode.photoIntervalMultiplier).coerceAtLeast(5),
                    jpegQuality = photoQuality
                )
                photoPipeline?.start()
            }

            if (sensorEnabled) {
                sensorPipeline = SensorPipeline(
                    context = this@EvidenceDiagnosticsService,
                    activeLocations = activeLocations,
                    sessionId = selectedSession,
                    intervalMs = powerMode.sensorIntervalMs
                )
                sensorPipeline?.start()
            }

            isDiagnosticsRunning = true
            AlarmKeeper.schedule(applicationContext)
            Log.i(TAG, "Diagnostics started: session=$selectedSession, mode=${powerMode.id}")
        }
    }

    private fun stopDiagnostics(clearDesired: Boolean) {
        if (!isDiagnosticsRunning && locationPipeline == null && photoPipeline == null &&
            sensorPipeline == null && wakeLock == null
        ) return

        isDiagnosticsRunning = false
        serviceJob?.cancel()
        serviceJob = null

        locationPipeline?.stop()
        locationPipeline = null

        photoPipeline?.stop()
        photoPipeline = null

        sensorPipeline?.stop()
        sensorPipeline = null

        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null

        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foregroundStarted = false
        }

        if (clearDesired) {
            CoroutineScope(Dispatchers.IO).launch {
                settings?.setDesiredDiagnosticsActive(false)
            }
        }

        Log.i(TAG, "Diagnostics service stopped")
        stopSelf()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun createSessionId(): String {
        return java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
    }
}
