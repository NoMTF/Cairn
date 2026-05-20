package com.cairn.app.trigger

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.cairn.app.service.RecordingService

/**
 * 音量键三连击监听 — 屏幕熄灭时 [Vol-][Vol-][Vol-] 0.5 秒内 → 立刻开始录音。
 *
 * 使用 AccessibilityService 实现，可以在熄屏状态下捕获按键事件。
 * 需要用户在系统无障碍设置中手动开启。
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VolumeKeyTrigger"
        private const val REQUIRED_CLICKS = 3
        private const val TIME_WINDOW_MS = 500L // 0.5 秒内三次
    }

    private val volumeDownTimestamps = mutableListOf<Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理无障碍事件，我们只用 onKeyEvent
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN) {

            val now = System.currentTimeMillis()
            volumeDownTimestamps.add(now)

            // 移除过期的时间戳
            volumeDownTimestamps.removeAll { now - it > TIME_WINDOW_MS }

            if (volumeDownTimestamps.size >= REQUIRED_CLICKS) {
                volumeDownTimestamps.clear()
                onTripleVolumeDown()
            }
        }

        return false // 不拦截按键，让系统正常处理音量
    }

    private fun onTripleVolumeDown() {
        Log.i(TAG, "Triple volume-down detected! Starting recording...")

        if (!RecordingService.isRunning) {
            RecordingService.start(this)
        }
    }
}
