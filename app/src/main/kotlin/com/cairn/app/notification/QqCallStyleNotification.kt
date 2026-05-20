package com.cairn.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.cairn.app.CairnApp
import com.cairn.app.R
import com.cairn.app.ui.MainActivity

/**
 * 伪装成"正在通话中"的常驻通知。
 *
 * 视觉：
 *   绿色话筒头像 · "联系人"
 *   "语音通话 · 通话中"
 *   计时器自动跑（Chronometer）
 *   红色挂断按钮 → 跳 MainActivity（不真停录音，UI 继续显示已连接）
 *
 * 用 NotificationCompat.CallStyle.forOngoingCall — Android 12+ 官方通话样式，
 * 锁屏可见、不被折叠、不被其它通知挤下去。
 *
 * 文案中性化（不出现 "QQ" 字样）以避免商标侵权，
 * 与 README "图标和文案为足够差异化的自绘版本" 原则一致。
 */
class QqCallStyleNotification(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 10086
    }

    private val callerStartedAt: Long = System.currentTimeMillis()

    fun createOngoingNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 挂断按钮 = 打开主屏（不停录音）
        val hangupIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val caller = Person.Builder()
            .setName(context.getString(R.string.notification_call_title))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_call_avatar))
            .setImportant(true)
            .build()

        val callStyle = NotificationCompat.CallStyle.forOngoingCall(caller, hangupIntent)

        return NotificationCompat.Builder(context, CairnApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_call_mic_small)
            .setContentTitle(context.getString(R.string.notification_call_title))
            .setContentText(context.getString(R.string.notification_call_text))
            .setContentIntent(mainIntent)
            .setStyle(callStyle)
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(callerStartedAt)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    fun updateDuration(durationMs: Long) {
        // Chronometer 自动跟，从 callerStartedAt 算起，无需手动刷新
    }
}
