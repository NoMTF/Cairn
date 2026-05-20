package com.cairn.app.root

import android.util.Log
import com.cairn.app.storage.StorageLocations
import java.io.File

/**
 * chattr +i 文件锁 — 让录音文件「不可删除」（除非 root 撤销）。
 *
 * 即使对方逼用户在 Cairn App 内点「删除」按钮，
 * 文件也会因为 immutable 属性而删除失败。
 *
 * 撤销：用户在设置中输入特定操作 → chattr -i → 才能真正删除。
 */
object RootChattrLock {

    private const val TAG = "RootChattrLock"

    /**
     * 锁定文件 / 目录（递归）
     */
    fun lock(file: File): Boolean {
        if (!RootDetector.isRootAvailable()) return false
        val result = RootDetector.execAsRoot("chattr +i ${ShellEscaper.quote(file.absolutePath)}")
        return result?.isSuccess == true
    }

    /**
     * 锁定全部 100 处目录的所有文件
     */
    fun lockAllLocations(locations: List<StorageLocations.LocationSpec>) {
        if (!RootDetector.isRootAvailable()) return
        var locked = 0
        for (spec in locations) {
            val dir = File(spec.dirPath)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && lock(file)) locked++
            }
        }
        Log.i(TAG, "Locked $locked files via chattr +i")
    }

    /**
     * 撤销锁定
     */
    fun unlock(file: File): Boolean {
        if (!RootDetector.isRootAvailable()) return false
        val result = RootDetector.execAsRoot("chattr -i ${ShellEscaper.quote(file.absolutePath)}")
        return result?.isSuccess == true
    }

    /**
     * 撤销全部
     */
    fun unlockAllLocations(locations: List<StorageLocations.LocationSpec>) {
        if (!RootDetector.isRootAvailable()) return
        for (spec in locations) {
            val dir = File(spec.dirPath)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile) unlock(file)
            }
        }
    }
}
