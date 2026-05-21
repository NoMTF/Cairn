package com.cairn.app.persist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cairn.app.service.EvidenceDiagnosticsService
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.RecoveryScanner
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启接收器。
 *
 * 手机重启后：
 * 1. 调度 AlarmKeeper（保活）
 * 2. RecoveryScanner 扫描全部 100 处，修复未正常关闭的录音文件
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.i(TAG, "Boot/package completed")

            // 1. 调度保活兜底
            AlarmKeeper.scheduleFromSettings(context)
            KeepAliveWorker.schedule(context)

            // 2. 异步修复未关闭的录音，并按用户期望状态恢复
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = SettingsStore(context.applicationContext)
                    val seed = settings.getDeviceSeed()
                    val ext = settings.extremeModeEnabledFlow.first()
                    val idx = settings.extremeEnabledIndicesFlow.first()
                    val registry = FolderRegistry(seed, ext, idx)
                    RecoveryScanner.repairAll(registry.getAll())

                    val desiredAudio = settings.desiredAudioActiveFlow.first()
                    val desiredDiagnostics = settings.desiredDiagnosticsActiveFlow.first()
                    val sessionId = settings.lastSessionIdFlow.first()
                    if (desiredAudio) RecordingService.start(context)
                    if (desiredDiagnostics) EvidenceDiagnosticsService.start(context, sessionId)

                    Log.i(TAG, "Recovery scan complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Recovery failed", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
