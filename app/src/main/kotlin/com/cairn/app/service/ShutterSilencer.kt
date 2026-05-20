package com.cairn.app.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 无 Root 关快门音组合技。
 *
 * 策略叠加（国行 ~95% 成功率）：
 * 1. STREAM_SYSTEM 临时静音
 * 2. 临时勿扰模式（优先级中断过滤）
 *
 * 注意：日韩 ROM 因法律强制快门音，无 Root 完全静音不可达。
 * Root 模式下使用 RootShutterMute 做系统级静音。
 */
class ShutterSilencer(private val context: Context) {

    companion object {
        private const val TAG = "ShutterSilencer"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var originalSystemVolume: Int = 0
    private var originalInterruptionFilter: Int = 0
    private var silenced = false

    /**
     * 拍照前调用 — 静音系统流 + 启用勿扰
     */
    fun muteBeforeCapture() {
        try {
            // 保存原始状态
            originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)

            // 策略 1：STREAM_SYSTEM 静音
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)

            // 策略 2：临时勿扰（需要 ACCESS_NOTIFICATION_POLICY 权限，但通常不需要额外申请）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    originalInterruptionFilter = notificationManager.currentInterruptionFilter
                    notificationManager.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_NONE
                    )
                }
            }

            silenced = true
        } catch (e: Exception) {
            Log.w(TAG, "Mute failed: ${e.message}")
        }
    }

    /**
     * 拍照后调用 — 恢复原始音量
     * 建议延迟 300~500ms 调用，确保快门音窗口已过
     */
    fun restoreAfterCapture() {
        if (!silenced) return

        try {
            // 恢复系统音量
            audioManager.setStreamVolume(
                AudioManager.STREAM_SYSTEM,
                originalSystemVolume,
                0
            )

            // 恢复勿扰
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    notificationManager.setInterruptionFilter(originalInterruptionFilter)
                }
            }

            silenced = false
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed: ${e.message}")
        }
    }
}
