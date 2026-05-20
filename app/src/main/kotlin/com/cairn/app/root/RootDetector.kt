package com.cairn.app.root

import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Root 检测 — 启动时调用一次。
 *
 * 用 libsu 库：调 Shell.rootAccess() 测试 su 是否可用。
 * 检测结果缓存。
 */
object RootDetector {

    private const val TAG = "RootDetector"
    private var cachedResult: Boolean? = null

    init {
        // 配置 libsu：超时 10 秒，不在主线程阻塞
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }

    /**
     * 检测 root（异步）— 第一次调用会触发授权对话框
     */
    fun isRootAvailable(): Boolean {
        cachedResult?.let { return it }

        val result = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.w(TAG, "Root detect failed: ${e.message}")
            false
        }

        cachedResult = result
        Log.i(TAG, "Root available: $result")
        return result
    }

    /**
     * 强制重新检测（不用缓存）
     */
    fun recheck(): Boolean {
        cachedResult = null
        return isRootAvailable()
    }

    /**
     * 执行 root 命令，返回输出
     */
    fun execAsRoot(vararg commands: String): Shell.Result? {
        if (!isRootAvailable()) return null
        return try {
            Shell.cmd(*commands).exec()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: ${commands.joinToString("; ")}", e)
            null
        }
    }
}
