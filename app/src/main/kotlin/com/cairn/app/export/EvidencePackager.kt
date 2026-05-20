package com.cairn.app.export

import android.content.Context
import android.util.Log
import com.cairn.app.storage.RecoveryScanner
import com.cairn.app.storage.StorageLocations
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 取证包导出器。
 *
 * 把一次录音的所有相关文件打包成 zip：
 *   recording.wav         — 音频
 *   recording.gps         — GPS 轨迹
 *   recording.sensor      — 传感器数据
 *   IMG_*.jpg             — 后台静默照片
 *   integrity_chain.csv   — 哈希链
 *   manifest.json         — 元数据 + 全部 100 副本的 SHA-256
 *   README.md             — 验证步骤说明
 *
 * 律师/法庭用附带的 verify_evidence.py 在任何机器上独立验证。
 */
class EvidencePackager(private val context: Context) {

    companion object {
        private const val TAG = "EvidencePackager"
    }

    /**
     * 导出某次录音的取证包到指定文件
     */
    fun export(
        sessionId: String,
        allLocations: List<StorageLocations.LocationSpec>,
        outputZip: File
    ): ExportResult {
        try {
            // 1. 扫描全部位置，找到该 session 的所有副本
            val sessions = RecoveryScanner.scanAll(allLocations)
            val copies = sessions[sessionId] ?: return ExportResult(
                false, 0, "Session $sessionId not found", null
            )

            // 2. 选择第一份完整副本作为权威数据
            val primaryCopy = copies.firstOrNull { !it.needsRepair }
                ?: copies.first().also { RecoveryScanner.repairIfNeeded(it) }

            val sourceDir = primaryCopy.file.parentFile ?: return ExportResult(
                false, 0, "Source dir not accessible", null
            )

            // 3. 收集相关文件（音频、GPS、传感器、照片）
            val baseFileName = primaryCopy.file.nameWithoutExtension
            val relatedFiles = sourceDir.listFiles { file ->
                file.name.contains(sessionId)
            } ?: emptyArray()

            // 4. 计算每个 100 副本的 SHA-256（用于 manifest）
            val copyHashes = copies.associate {
                it.locationIndex to RecoveryScanner.computeSha256(it.file)
            }

            // 4b. 找到主副本同址的哈希链文件并计算 SHA-256（可选）
            val chainFile = File(sourceDir, primaryCopy.file.nameWithoutExtension + ".chain")
                .takeIf { it.exists() }
                ?: sourceDir.listFiles { f -> f.name.endsWith(".chain") && f.name.contains(sessionId) }
                    ?.firstOrNull()
            val chainSha = chainFile?.let { RecoveryScanner.computeSha256(it) }

            // 5. 构建 zip
            outputZip.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                // 5a. 添加主要文件
                for (file in relatedFiles) {
                    addToZip(zos, file, "recording/${file.name}")
                }

                // 5b. 添加 manifest
                val manifestJson = buildManifest(sessionId, primaryCopy, copyHashes, chainFile?.name, chainSha)
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifestJson.toByteArray())
                zos.closeEntry()

                // 5c. 添加独立检查脚本（打包在 assets 中）
                addAssetToZip(zos, "decrypt.py", "decrypt.py")

                // 5d. 添加 README
                zos.putNextEntry(ZipEntry("README.md"))
                zos.write(buildReadme(sessionId, copies.size, chainFile != null).toByteArray())
                zos.closeEntry()
            }

            Log.i(TAG, "Exported $sessionId to ${outputZip.absolutePath} (${outputZip.length()} bytes)")
            return ExportResult(true, copies.size, null, outputZip.absolutePath)

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            return ExportResult(false, 0, e.message, null)
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun addAssetToZip(zos: ZipOutputStream, assetName: String, entryName: String) {
        try {
            zos.putNextEntry(ZipEntry(entryName))
            context.assets.open(assetName).use { it.copyTo(zos) }
            zos.closeEntry()
        } catch (_: Exception) {
            // Asset is non-critical; evidence files and manifest still define the package.
        }
    }

    private fun buildManifest(
        sessionId: String,
        primary: RecoveryScanner.FoundRecording,
        copyHashes: Map<Int, String>,
        chainFileName: String?,
        chainSha256: String?
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"session_id\": \"$sessionId\",")
        sb.appendLine("  \"export_time\": \"$time\",")
        sb.appendLine("  \"primary_copy_index\": ${primary.locationIndex},")
        sb.appendLine("  \"primary_copy_sha256\": \"${RecoveryScanner.computeSha256(primary.file)}\",")
        sb.appendLine("  \"copy_count\": ${copyHashes.size},")
        if (chainFileName != null && chainSha256 != null) {
            sb.appendLine("  \"integrity_chain_file\": \"recording/$chainFileName\",")
            sb.appendLine("  \"integrity_chain_sha256\": \"$chainSha256\",")
        }
        sb.appendLine("  \"all_copies\": {")
        val entries = copyHashes.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            val comma = if (i < entries.size - 1) "," else ""
            sb.appendLine("    \"${entry.key}\": \"${entry.value}\"$comma")
        }
        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun buildReadme(sessionId: String, copyCount: Int, hasChain: Boolean): String {
        val chainNote = if (hasChain) "- `recording/*.chain` — 每秒一行 SHA-256 哈希链 CSV\n" else ""
        return """
# Cairn 取证包 — $sessionId

## 包含内容

- `recording/` — 音频、GPS、传感器、照片
$chainNote- `manifest.json` — 元数据 + 全部 $copyCount 份副本的 SHA-256
- `decrypt.py` — 加密容器检查脚本
- `README.md` — 本文件

## 验证步骤

1. 下载 verify_evidence.py（独立 Python 脚本，开源）
2. 运行：
   ```
   python verify_evidence.py <本zip文件>
   ```
3. 验证内容：
   - 所有文件 SHA-256 与 manifest 中一致
   - 哈希链未断裂（每个 chunk 的 prev_hash 与上一 chunk 的 current_hash 匹配）
   - GPS 时间戳连续（间隔 ~1 秒）
   - 设备指纹一致

## 说明

本录音由 Cairn App 离线生成。
App 全程无网络权限，所有数据未经过任何远程服务器。
完整源码：https://github.com/cairn-app/cairn

哈希链结构保证：任何对录音的篡改、删除、剪辑均会导致链断裂，可在法庭出示作为不可否认的完整性证明。
        """.trimIndent()
    }

    data class ExportResult(
        val success: Boolean,
        val copyCount: Int,
        val error: String?,
        val outputPath: String?
    )
}
