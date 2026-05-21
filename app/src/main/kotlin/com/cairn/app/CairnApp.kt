package com.cairn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.cairn.app.persist.AlarmKeeper
import com.cairn.app.persist.KeepAliveWorker
import com.cairn.app.service.EvidenceDiagnosticsService
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.RecoveryScanner
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CairnApp : Application() {

    companion object {
        const val CHANNEL_RECORDING = "cairn_recording"
        private const val TAG = "CairnApp"
        lateinit var instance: CairnApp
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // 启动时扫描全部 100 处副本，修复未正常关闭的录音文件
        triggerRecoveryScan()
        triggerDesiredRecovery()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_RECORDING,
                "通话状态",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "语音通话状态显示"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun triggerRecoveryScan() {
        appScope.launch {
            try {
                val settings = SettingsStore(applicationContext)
                val seed = settings.getDeviceSeed()
                val ext = settings.extremeModeEnabledFlow.first()
                val idx = settings.extremeEnabledIndicesFlow.first()
                val registry = FolderRegistry(seed, ext, idx)
                RecoveryScanner.repairAll(registry.getAll())
                Log.i(TAG, "Recovery scan complete on app start")
            } catch (e: Exception) {
                Log.e(TAG, "Recovery scan failed", e)
            }
        }
    }

    private fun triggerDesiredRecovery() {
        appScope.launch {
            try {
                val settings = SettingsStore(applicationContext)
                val desiredAudio = settings.desiredAudioActiveFlow.first()
                val desiredDiagnostics = settings.desiredDiagnosticsActiveFlow.first()
                val sessionId = settings.lastSessionIdFlow.first()
                if (desiredAudio || desiredDiagnostics) {
                    AlarmKeeper.scheduleFromSettings(applicationContext)
                    KeepAliveWorker.schedule(applicationContext)
                }
                if (desiredAudio) RecordingService.start(applicationContext)
                if (desiredDiagnostics) EvidenceDiagnosticsService.start(applicationContext, sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Desired recovery failed", e)
            }
        }
    }
}
