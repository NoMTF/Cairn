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
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.cairn.app.START_RECORDING"
        const val ACTION_STOP = "com.cairn.app.STOP_RECORDING"

        var instance: RecordingService? = null
            private set

        val isRunning: Boolean get() = instance?.isRecording == true

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var audioPipeline: AudioPipeline? = null
    private var locationPipeline: LocationPipeline? = null
    private var photoPipeline: PhotoPipeline? = null
    private var sensorPipeline: SensorPipeline? = null
    private var storageWatchdog: StorageWatchdog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationHelper: QqCallStyleNotification? = null
    private var settings: SettingsStore? = null
    private var serviceJob: Job? = null
    private var foregroundStarted = false

    var isRecording = false
        private set

    var aliveWriters: Int = 0
        private set
    var totalWriters: Int = 0
        private set
    var bytesWritten: Long = 0
        private set
    var storageAvailablePercent: Float = 100f
        private set

    val durationMs: Long get() = audioPipeline?.durationMs ?: 0
    val sessionId: String? get() = audioPipeline?.sessionId

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationHelper = QqCallStyleNotification(this)
        settings = SettingsStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        instance = null
        super.onDestroy()
    }

    private fun startRecording() {
        if (isRecording) return

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.e(TAG, "Cannot start recording without RECORD_AUDIO permission")
            stopSelf()
            return
        }

        try {
            val notification = notificationHelper!!.createOngoingNotification()
            startForeground(QqCallStyleNotification.NOTIFICATION_ID, notification)
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to enter foreground", e)
            stopSelf()
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cairn:recording").apply {
            acquire(8 * 60 * 60 * 1000L)
        }

        serviceJob = CoroutineScope(Dispatchers.Default).launch {
            val settingsLocal = settings ?: SettingsStore(applicationContext)

            val deviceSeed = settingsLocal.getDeviceSeed()
            val extremeEnabled = settingsLocal.extremeModeEnabledFlow.first()
            val extremeIndices = settingsLocal.extremeEnabledIndicesFlow.first()
            val photoEnabled = settingsLocal.photoEnabledFlow.first()
            val photoInterval = settingsLocal.photoIntervalFlow.first()
            val photoQuality = settingsLocal.photoQualityFlow.first()
            val storageThreshold = settingsLocal.storageThresholdFlow.first()
            val gpsEnabled = settingsLocal.gpsEnabledFlow.first()
            val sensorEnabled = settingsLocal.sensorEnabledFlow.first()
            val audioSampleRate = settingsLocal.audioSampleRateFlow.first()

            val registry = FolderRegistry(deviceSeed, extremeEnabled, extremeIndices)
            val activeLocations = registry.getActive()
            val fingerprint = getDeviceFingerprint()

            totalWriters = activeLocations.size

            audioPipeline = AudioPipeline(
                context = this@RecordingService,
                activeLocations = activeLocations,
                deviceFingerprint = fingerprint,
                sampleRate = audioSampleRate,
                onStatusUpdate = { status ->
                    notificationHelper?.updateDuration(status.durationMs)
                    aliveWriters = status.aliveWriters
                    totalWriters = status.totalWriters
                    bytesWritten = status.bytesWritten
                }
            )
            val started = audioPipeline?.start() ?: false
            if (!started) {
                Log.e(TAG, "AudioPipeline failed to start")
                isRecording = false
                cleanupAfterStop()
                stopSelf()
                return@launch
            }

            val activeSessionId = audioPipeline!!.sessionId

            if (gpsEnabled && hasAnyLocationPermission()) {
                locationPipeline = LocationPipeline(this@RecordingService, activeLocations, activeSessionId)
                locationPipeline?.start()
            }

            if (photoEnabled && hasPermission(Manifest.permission.CAMERA)) {
                photoPipeline = PhotoPipeline(
                    this@RecordingService,
                    activeLocations,
                    activeSessionId,
                    photoInterval,
                    photoQuality
                )
                photoPipeline?.start()
            }

            if (sensorEnabled) {
                sensorPipeline = SensorPipeline(this@RecordingService, activeLocations, activeSessionId)
                sensorPipeline?.start()
            }

            storageWatchdog = StorageWatchdog(
                thresholdPercent = storageThreshold,
                onPause = { stopForLowStorage() },
                onResume = { Log.i(TAG, "Storage recovered; waiting for user to start a new session") }
            )

            isRecording = true
            Log.i(
                TAG,
                "Recording started: session=$activeSessionId, writers=${activeLocations.size}, extreme=$extremeEnabled"
            )

            while (isRecording) {
                delay(1000)
                storageWatchdog?.check()
                storageAvailablePercent = storageWatchdog?.getAvailablePercent() ?: 100f
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording && audioPipeline == null && locationPipeline == null &&
            photoPipeline == null && sensorPipeline == null && wakeLock == null
        ) return

        isRecording = false
        serviceJob?.cancel()
        serviceJob = null
        cleanupAfterStop()
        stopSelf()

        Log.i(TAG, "Recording service stopped")
    }

    private fun stopForLowStorage() {
        Log.w(TAG, "Storage low; closing current session cleanly")
        stopRecording()
    }

    private fun cleanupAfterStop() {
        audioPipeline?.stop()
        audioPipeline = null

        locationPipeline?.stop()
        locationPipeline = null

        photoPipeline?.stop()
        photoPipeline = null

        sensorPipeline?.stop()
        sensorPipeline = null

        storageWatchdog = null

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
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun getDeviceFingerprint(): String {
        return "${Build.MANUFACTURER}_${Build.MODEL}_${Build.FINGERPRINT}".take(128)
    }
}
