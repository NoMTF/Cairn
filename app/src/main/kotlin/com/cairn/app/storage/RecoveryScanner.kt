package com.cairn.app.storage

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 恢复扫描器。
 *
 * 功能：
 * 1. 启动时扫描全部 100 处目录，寻找未正常关闭的录音文件
 * 2. 用 .len sidecar 修复 WAV header
 * 3. SHA-256 校验完整性
 * 4. 恢复时：从任一存活副本还原
 */
object RecoveryScanner {

    private const val TAG = "RecoveryScanner"

    data class FoundRecording(
        val sessionId: String,
        val locationIndex: Int,
        val file: File,
        val sizeBytes: Long,
        val needsRepair: Boolean,   // .len 文件存在 = 上次没正常关闭
        val sha256: String?,
        val hasGps: Boolean,
        val hasSensor: Boolean
    )

    /**
     * 扫描全部位置，找到所有录音文件
     */
    fun scanAll(allLocations: List<StorageLocations.LocationSpec>): Map<String, List<FoundRecording>> {
        val results = mutableMapOf<String, MutableList<FoundRecording>>()

        for (spec in allLocations) {
            val dir = File(spec.dirPath)
            if (!dir.exists()) continue

            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory || file.name == ".nomedia") continue
                if (file.name.endsWith(".len") || file.name.endsWith(".gps") ||
                    file.name.endsWith(".sensor") || file.name.endsWith(".chain")) continue

                // 从文件名提取 sessionId
                val sessionId = extractSessionId(file.name, spec) ?: continue

                val lenFile = File(file.absolutePath + ".len")
                val needsRepair = lenFile.exists()

                val gpsFile = File(dir, StorageLocations.generateGpsFileName(spec, sessionId))
                val sensorFile = File(dir, "${StorageLocations.generateFileName(spec, sessionId)}.sensor")

                val recording = FoundRecording(
                    sessionId = sessionId,
                    locationIndex = spec.index,
                    file = file,
                    sizeBytes = file.length(),
                    needsRepair = needsRepair,
                    sha256 = null, // 按需计算，避免扫描时太慢
                    hasGps = gpsFile.exists(),
                    hasSensor = sensorFile.exists()
                )

                results.getOrPut(sessionId) { mutableListOf() }.add(recording)
            }
        }

        Log.i(TAG, "Found ${results.size} sessions across ${results.values.sumOf { it.size }} copies")
        return results
    }

    /**
     * 修复未正常关闭的文件
     */
    fun repairIfNeeded(recording: FoundRecording) {
        if (!recording.needsRepair) return

        val lenFile = File(recording.file.absolutePath + ".len")
        if (!lenFile.exists()) return

        try {
            EncryptedChunkWriter.repairFromLenFile(recording.file, lenFile)
            Log.i(TAG, "Repaired encrypted container: ${recording.file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Repair failed: ${recording.file.name}", e)
        }
    }

    /**
     * 修复所有未正常关闭的文件
     */
    fun repairAll(allLocations: List<StorageLocations.LocationSpec>) {
        val sessions = scanAll(allLocations)
        var repaired = 0
        for ((_, copies) in sessions) {
            for (copy in copies) {
                if (copy.needsRepair) {
                    repairIfNeeded(copy)
                    repaired++
                }
            }
        }
        Log.i(TAG, "Repaired $repaired files")
    }

    /**
     * 计算文件 SHA-256
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 从文件名提取 sessionId
     */
    private fun extractSessionId(fileName: String, spec: StorageLocations.LocationSpec): String? {
        val name = fileName
            .removePrefix(spec.filePrefix)
            .removeSuffix(spec.fileSuffix)

        // sessionId 格式：时间戳，如 20250517_143022
        return if (name.matches(Regex("\\d{8}_\\d{6}.*"))) name.take(15) else null
    }
}
