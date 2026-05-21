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
 * Packages one local session with its manifest and sidecars for later verification.
 */
class EvidencePackager(private val context: Context) {

    companion object {
        private const val TAG = "EvidencePackager"
    }

    /**
     * Export a session package to the requested zip file.
     */
    fun export(
        sessionId: String,
        allLocations: List<StorageLocations.LocationSpec>,
        outputZip: File
    ): ExportResult {
        try {
            // Scan all configured locations and collect copies for this session.
            val sessions = RecoveryScanner.scanAll(allLocations)
            val copies = sessions[sessionId] ?: return ExportResult(
                false, 0, "Session $sessionId not found", null
            )

            val primaryCopy = copies.firstOrNull { !it.needsRepair }
                ?: copies.first().also { RecoveryScanner.repairIfNeeded(it) }

            val sourceDir = primaryCopy.file.parentFile ?: return ExportResult(
                false, 0, "Source dir not accessible", null
            )

            val relatedFiles = sourceDir.listFiles { file ->
                file.name.contains(sessionId)
            } ?: emptyArray()

            val copyHashes = copies.associate {
                it.locationIndex to RecoveryScanner.computeSha256(it.file)
            }

            val chainFile = File(sourceDir, primaryCopy.file.nameWithoutExtension + ".chain")
                .takeIf { it.exists() }
                ?: sourceDir.listFiles { f -> f.name.endsWith(".chain") && f.name.contains(sessionId) }
                    ?.firstOrNull()
            val chainSha = chainFile?.let { RecoveryScanner.computeSha256(it) }

            outputZip.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                for (file in relatedFiles) {
                    addToZip(zos, file, "recording/${file.name}")
                }

                val manifestJson = buildManifest(sessionId, primaryCopy, copyHashes, chainFile?.name, chainSha)
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifestJson.toByteArray())
                zos.closeEntry()

                addAssetToZip(zos, "decrypt.py", "decrypt.py")

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
        val chainNote = if (hasChain) "- `recording/*.chain` - SHA-256 连续性链，每个音频分块一行\n" else ""
        return """
# FastLink 诊断包 - $sessionId

此包由 FastLink VPN 从本机存储导出。它面向极端个人安全记录场景设计，不包含网络上传路径。

## 内容

- `recording/` - CNCE 加密音频容器和 sidecar 文件
$chainNote- `manifest.json` - 会话元数据和 $copyCount 份可用副本的 SHA-256
- `decrypt.py` - CNCE 容器检查脚本
- `README.md` - 本文件

## 验证

1. 使用仓库中的 `verify/verify_evidence.py`。
2. 运行：
   ```
   python verify_evidence.py <本zip文件>
   ```
3. 脚本会检查 manifest、主副本哈希、CNCE 标记、GPS 连续性和哈希链连续性。

## 说明

音频存储在受 Android Keystore 保护的 AES-GCM CNCE 容器中。`decrypt.py` 当前用于检查容器结构；完整离线解密需要后续的导出密码重加密流程。

源码仓库：https://github.com/NoMTF/Cairn
        """.trimIndent()
    }

    data class ExportResult(
        val success: Boolean,
        val copyCount: Int,
        val error: String?,
        val outputPath: String?
    )
}
