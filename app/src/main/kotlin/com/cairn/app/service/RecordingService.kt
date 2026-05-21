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
import com.cairn.app.persist.KeepAliveWorker
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

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
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "Unable to request recording service start", it)
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
    private var storageWatchdog: StorageWatchdog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationHelper: QqCallStyleNotification? = null
    private var settings: SettingsStore? = null
    private var serviceJob: Job? = null
    private var foregroundStarted = false
    private var activeSessionId: String? = null
    private var primarySessionDir: File? = null

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
            ACTION_STOP -> stopRecording(clearDesired = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording(clearDesired = false)
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
            val storageThreshold = settingsLocal.storageThresholdFlow.first()
            val audioSampleRate = settingsLocal.audioSampleRateFlow.first()

            val registry = FolderRegistry(deviceSeed, extremeEnabled, extremeIndices)
            val activeLocations = registry.getActive()
            val fingerprint = getDeviceFingerprint()

            totalWriters = activeLocations.size
            primarySessionDir = activeLocations.firstOrNull()?.let { File(it.dirPath) }

            audioPipeline = AudioPipeline(
                context = this@RecordingService,
                activeLocations = activeLocations,
                deviceFingerprint = fingerprint,
                sampleRate = audioSampleRate,
                onStatusUpdate = { status ->
                    notificationHelper?.updateDuration(status.durationMs)
                    aliveWriters = status.aliveWriters
                    totalWriters = status.totalWriters
                    bytesWritten = computePrimarySessionBytes()
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

            activeSessionId = audioPipeline!!.sessionId
            settingsLocal.setLastSessionId(activeSessionId)
            settingsLocal.setDesiredAudioActive(true)
            settingsLocal.setDesiredDiagnosticsActive(true)
            EvidenceDiagnosticsService.start(this@RecordingService, activeSessionId)
            AlarmKeeper.scheduleFromSettings(applicationContext)
            KeepAliveWorker.schedule(applicationContext)

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
                bytesWritten = computePrimarySessionBytes()
            }
        }
    }

    private fun stopRecording(clearDesired: Boolean) {
        if (!isRecording && audioPipeline == null && wakeLock == null) return

        isRecording = false
        serviceJob?.cancel()
        serviceJob = null
        if (clearDesired) {
            CoroutineScope(Dispatchers.IO).launch {
                settings?.setDesiredAudioActive(false)
            }
        }
        cleanupAfterStop()
        stopSelf()

        Log.i(TAG, "Recording service stopped")
    }

    private fun stopForLowStorage() {
        Log.w(TAG, "Storage low; closing current session cleanly")
        stopRecording(clearDesired = true)
    }

    private fun cleanupAfterStop() {
        audioPipeline?.stop()
        audioPipeline = null

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
        activeSessionId = null
        primarySessionDir = null
    }

    private fun computePrimarySessionBytes(): Long {
        val session = activeSessionId ?: return bytesWritten
        val dir = primarySessionDir ?: return bytesWritten
        return runCatching {
            dir.listFiles { file -> file.name.contains(session) }
                ?.sumOf { it.length() }
                ?: bytesWritten
        }.getOrDefault(bytesWritten)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getDeviceFingerprint(): String {
        return "${Build.MANUFACTURER}_${Build.MODEL}_${Build.FINGERPRINT}".take(128)
    }
}
