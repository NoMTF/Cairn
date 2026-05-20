package com.cairn.app.service

import android.os.Environment
import android.os.StatFs
import android.util.Log

/**
 * 存储空间监控犬。
 *
 * 每写 1 个 chunk（1 秒）调用 check()：
 * - 可用空间 < 阈值 → 暂停录音
 * - 恢复到 阈值+2% → 自动续录
 *
 * 默认阈值 10%，可调 1%~50%。
 */
class StorageWatchdog(
    private val thresholdPercent: Float = 10f,
    private val onPause: () -> Unit = {},
    private val onResume: () -> Unit = {}
) {
    companion object {
        private const val TAG = "StorageWatchdog"
        private const val HYSTERESIS_PERCENT = 2f // 恢复时需超阈值 2% 才恢复
    }

    private var isPaused = false

    /**
     * 每秒调用一次。返回 true = 可以继续录，false = 应暂停。
     */
    fun check(): Boolean {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val availableBytes = stat.availableBytes
        val totalBytes = stat.totalBytes
        val availablePercent = (availableBytes.toFloat() / totalBytes.toFloat()) * 100f

        if (!isPaused && availablePercent < thresholdPercent) {
            // 触发暂停
            isPaused = true
            Log.w(TAG, "Storage low: %.1f%% < %.1f%%, pausing".format(availablePercent, thresholdPercent))
            onPause()
            return false
        }

        if (isPaused && availablePercent >= (thresholdPercent + HYSTERESIS_PERCENT)) {
            // 恢复
            isPaused = false
            Log.i(TAG, "Storage recovered: %.1f%%, resuming".format(availablePercent))
            onResume()
            return true
        }

        return !isPaused
    }

    /**
     * 获取当前可用存储百分比
     */
    fun getAvailablePercent(): Float {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return (stat.availableBytes.toFloat() / stat.totalBytes.toFloat()) * 100f
    }

    /**
     * 获取当前可用 MB
     */
    fun getAvailableMb(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return stat.availableBytes / (1024 * 1024)
    }
}
