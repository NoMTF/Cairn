package com.cairn.app.nuke

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.cairn.app.root.RootChattrLock
import com.cairn.app.root.RootDetector
import com.cairn.app.root.ShellEscaper
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.SecureWipe
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.*

/**
 * 核爆引擎 — 不可逆销毁所有数据。
 *
 * 触发流程：
 * 1. 停止录音服务（如在运行）
 * 2. Root 模式下：先 chattr -i 解锁所有文件
 * 3. SecureWipe DoD 3 遍覆写全部 100 处副本
 * 4. 删除文件
 * 5. 清 App 私有数据
 * 6. Root 模式下：pm uninstall --user 0 自卸载
 *
 * 全程静默 — 调用者看到的 UI 仍是"感谢反馈"。
 */
object NukeEngine {

    private const val TAG = "NukeEngine"

    /**
     * 启动核爆 — 异步执行，立即返回。
     * 调用者不应等待结果（让对方看到正常的 UI 反应）。
     */
    fun detonate(context: Context) {
        Log.w(TAG, "NUKE DETONATED")

        // 异步执行，不阻塞 UI
        CoroutineScope(Dispatchers.IO).launch {
            try {
                executeNuke(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Nuke failed", e)
                // 即使失败也尝试最后的兜底清理
                fallbackWipe(context)
            }
        }
    }

    private suspend fun executeNuke(context: Context) {
        // 1. 立即停止录音
        try {
            RecordingService.stop(context)
        } catch (e: Exception) {
            Log.w(TAG, "Stop recording failed: ${e.message}")
        }
        delay(200)

        // 2. 获取全部 100 处副本
        val settings = SettingsStore(context)
        val deviceSeed = settings.getDeviceSeed()
        val registry = FolderRegistry(deviceSeed, extremeMode = true)
        val allLocations = registry.getAll()

        // 3. Root 模式：解锁 chattr +i
        val hasRoot = RootDetector.isRootAvailable()
        if (hasRoot) {
            try {
                RootChattrLock.unlockAllLocations(allLocations)
            } catch (e: Exception) {
                Log.w(TAG, "Unlock chattr failed: ${e.message}")
            }
        }

        // 4. DoD 3 遍覆写 + 删除全部 100 处副本
        val wiped = SecureWipe.wipeAllLocations(allLocations)
        Log.i(TAG, "Wiped $wiped files across 100 locations")

        // 5. 清 App 私有数据
        clearAppData(context)

        // 6. Root 模式：自卸载
        if (hasRoot) {
            RootDetector.execAsRoot("pm uninstall --user 0 ${ShellEscaper.quote(context.packageName)}")
        }
    }

    /**
     * 清 App 私有数据（沙箱内的 cache / files / databases）
     */
    private fun clearAppData(context: Context) {
        try {
            // 删除 cache
            context.cacheDir?.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()

            // 删除 files
            context.filesDir?.let { dir ->
                dir.listFiles()?.forEach { it.deleteRecursively() }
            }

            // 删除 DataStore + databases
            context.getDir("datastore", Context.MODE_PRIVATE)?.deleteRecursively()

            // 调用 ActivityManager.clearApplicationUserData()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.clearApplicationUserData()
        } catch (e: Exception) {
            Log.e(TAG, "Clear app data failed", e)
        }
    }

    /**
     * 兜底擦除 — 最后的努力
     */
    private fun fallbackWipe(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.clearApplicationUserData()
        } catch (_: Exception) {
        }
    }
}
