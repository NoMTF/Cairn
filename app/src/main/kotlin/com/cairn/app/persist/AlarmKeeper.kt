package com.cairn.app.persist

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.cairn.app.service.RecordingService

/**
 * AlarmManager 保活守护 — 每 30 秒检查服务存活，不在则拉起。
 *
 * 保活七层之第五层。
 * 即使 Doze 模式下也用 setExactAndAllowWhileIdle 保证唤醒。
 */
class AlarmKeeper : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmKeeper"
        private const val INTERVAL_MS = 30_000L // 30 秒

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmKeeper::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

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
        // 检查服务是否存活
        if (!RecordingService.isRunning) {
            Log.w(TAG, "Service dead, reviving...")
            RecordingService.start(context)
        }

        // 重新调度下一次检查
        schedule(context)
    }
}
