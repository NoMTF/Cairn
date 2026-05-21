package com.cairn.app.persist

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.cairn.app.service.EvidenceDiagnosticsService
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AlarmManager 保活守护 — 只恢复用户明确开启过的线路状态。
 *
 * 保活七层之第五层。
 * 即使 Doze 模式下也用 setExactAndAllowWhileIdle 保证唤醒。
 */
class AlarmKeeper : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmKeeper"
        private const val INTERVAL_MS = 30_000L // 30 秒

        fun schedule(context: Context, intervalMs: Long = INTERVAL_MS) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmKeeper::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val triggerAt = SystemClock.elapsedRealtime() + intervalMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi
                )
            } else {
                am.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi
                )
            }
        }

        fun scheduleFromSettings(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                val settings = SettingsStore(context.applicationContext)
                val mode = settings.powerModeFlow.first()
                schedule(context, mode.keeperIntervalMs)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmKeeper::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(context.applicationContext)
                val desiredAudio = settings.desiredAudioActiveFlow.first()
                val desiredDiagnostics = settings.desiredDiagnosticsActiveFlow.first()
                val sessionId = settings.lastSessionIdFlow.first()
                val mode = settings.powerModeFlow.first()

                if (desiredAudio && !RecordingService.isRunning) {
                    Log.w(TAG, "Audio service dead, reviving...")
                    RecordingService.start(context)
                }
                if (desiredDiagnostics && !EvidenceDiagnosticsService.isRunning) {
                    Log.w(TAG, "Diagnostics service dead, reviving...")
                    EvidenceDiagnosticsService.start(context, sessionId)
                }

                if (desiredAudio || desiredDiagnostics) {
                    schedule(context, mode.keeperIntervalMs)
                } else {
                    cancel(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
