package com.cairn.app.export

import android.content.Context
import android.util.Log
import com.cairn.app.storage.CnceAudioReader
import com.cairn.app.storage.RecoveryScanner
import com.cairn.app.storage.StorageLocations
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a human-readable evidence package from one encrypted local session.
 *
 * The internal CNCE/multi-copy structure remains the crash-safe storage format;
 * export produces WAV/JPG/CSV files that can be opened without helper scripts.
 */
class EvidencePackager(@Suppress("UNUSED_PARAMETER") context: Context) {

    companion object {
        private const val TAG = "EvidencePackager"
        private const val BUFFER_SIZE = 64 * 1024
    }

    data class ExportedFile(
        val path: String,
        val sha256: String,
        val sizeBytes: Long
    )

    data class AudioExport(
        val file: ExportedFile,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val chunkCount: Int,
        val pcmBytes: Long
    )

    /**
     * Export a session package to the requested zip file.
     */
    fun export(
        sessionId: String,
        allLocations: List<StorageLocations.LocationSpec>,
        outputZip: File
    ): ExportResult {
        try {
            val sessions = RecoveryScanner.scanAll(allLocations)
            val copies = sessions[sessionId] ?: return ExportResult(
                false, 0, "Session $sessionId not found", null
            )

            val primaryCopy = copies.firstOrNull { !it.needsRepair }
                ?: copies.first().also { RecoveryScanner.repairIfNeeded(it) }
            RecoveryScanner.repairIfNeeded(primaryCopy)

            val primarySha256 = RecoveryScanner.computeSha256(primaryCopy.file)
            val copyHashes = copies.associate {
                it.locationIndex to RecoveryScanner.computeSha256(it.file)
            }
            val sourceDirs = copies.mapNotNull { it.file.parentFile }.distinctBy { it.absolutePath }
            val relatedFiles = sourceDirs.flatMap { dir ->
                dir.listFiles { file -> file.name.contains(sessionId) }?.toList() ?: emptyList()
            }

            val gpsFile = findBestSidecar(sourceDirs, sessionId, ".gps")
            val sensorFile = findBestSidecar(sourceDirs, sessionId, ".sensor")
            val chainFile = findBestSidecar(sourceDirs, sessionId, ".chain")
            val photoFiles = findBestPhotoSet(sourceDirs, sessionId)

            outputZip.parentFile?.mkdirs()

            var audioExport: AudioExport? = null
            val exportedFiles = mutableListOf<ExportedFile>()

            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                val reader = CnceAudioReader(primaryCopy.file)
                audioExport = addWavEntry(
                    zos = zos,
                    reader = reader,
                    entryName = "audio/session_$sessionId.wav"
                )
                exportedFiles += audioExport!!.file

                for ((index, photo) in photoFiles.withIndex()) {
                    val entryName = "photos/IMG_${sessionId}_${"%04d".format(index)}.jpg"
                    exportedFiles += addFileEntry(zos, photo, entryName)
                }

                gpsFile?.let { exportedFiles += addFileEntry(zos, it, "gps/gps.csv") }
                sensorFile?.let { exportedFiles += addFileEntry(zos, it, "sensors/sensors.csv") }
                chainFile?.let { exportedFiles += addFileEntry(zos, it, "integrity/integrity_chain.csv") }

                val manifestJson = buildManifest(
                    sessionId = sessionId,
                    primary = primaryCopy,
                    primarySha256 = primarySha256,
                    copyHashes = copyHashes,
                    audioExport = audioExport!!,
                    exportedFiles = exportedFiles,
                    gpsRecords = gpsFile?.countDataRows() ?: 0,
                    sensorRecords = sensorFile?.countDataRows() ?: 0,
                    chainRecords = chainFile?.countDataRows() ?: 0,
                    photoCount = photoFiles.size,
                    relatedFiles = relatedFiles
                )
                val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
                val manifestFile = addBytesEntry(zos, manifestBytes, "manifest.json")

                val readmeBytes = buildReadme(
                    sessionId = sessionId,
                    copyCount = copies.size,
                    photoCount = photoFiles.size,
                    gpsRecords = gpsFile?.countDataRows() ?: 0,
                    sensorRecords = sensorFile?.countDataRows() ?: 0
                ).toByteArray(Charsets.UTF_8)
                addBytesEntry(zos, readmeBytes, "README.md")

                Log.i(TAG, "Manifest hash for $sessionId: ${manifestFile.sha256}")
            }

            Log.i(TAG, "Exported $sessionId to ${outputZip.absolutePath} (${outputZip.length()} bytes)")
            return ExportResult(true, copies.size, null, outputZip.absolutePath)

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            return ExportResult(false, 0, e.message, null)
        }
    }

    private fun addWavEntry(
        zos: ZipOutputStream,
        reader: CnceAudioReader,
        entryName: String
    ): AudioExport {
        val header = reader.inspect()
        require(header.bitsPerSample == 16) {
            "Only 16-bit PCM WAV export is supported; got ${header.bitsPerSample}"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L

        zos.putNextEntry(ZipEntry(entryName))

        fun write(bytes: ByteArray) {
            zos.write(bytes)
            digest.update(bytes)
            written += bytes.size
        }

        write(buildWavHeader(header))
        val decryptHeader = reader.decryptChunks { chunk -> write(chunk) }
        zos.closeEntry()

        require(decryptHeader.actualChunks == header.actualChunks) { "CNCE chunk scan changed during export" }

        return AudioExport(
            file = ExportedFile(entryName, digest.hex(), written),
            sampleRate = header.sampleRate,
            channels = header.channels,
            bitsPerSample = header.bitsPerSample,
            chunkCount = header.actualChunks,
            pcmBytes = header.pcmBytes
        )
    }

    private fun addFileEntry(zos: ZipOutputStream, file: File, entryName: String): ExportedFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                zos.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
            }
        }
        zos.closeEntry()

        return ExportedFile(entryName, digest.hex(), written)
    }

    private fun addBytesEntry(zos: ZipOutputStream, bytes: ByteArray, entryName: String): ExportedFile {
        val digest = MessageDigest.getInstance("SHA-256")
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(bytes)
        digest.update(bytes)
        zos.closeEntry()
        return ExportedFile(entryName, digest.hex(), bytes.size.toLong())
    }

    private fun buildWavHeader(header: CnceAudioReader.Header): ByteArray {
        val dataSize = header.pcmBytes
        val riffSize = 36L + dataSize
        require(dataSize <= 0xFFFF_FFFFL && riffSize <= 0xFFFF_FFFFL) {
            "WAV export is limited to 4 GiB"
        }

        val byteRate = header.sampleRate * header.channels * header.bitsPerSample / 8
        val blockAlign = header.channels * header.bitsPerSample / 8
        val out = ByteArray(44)
        var offset = 0

        fun ascii(value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, out, offset, bytes.size)
            offset += bytes.size
        }

        fun intLe(value: Long) {
            out[offset++] = (value and 0xFF).toByte()
            out[offset++] = ((value shr 8) and 0xFF).toByte()
            out[offset++] = ((value shr 16) and 0xFF).toByte()
            out[offset++] = ((value shr 24) and 0xFF).toByte()
        }

        fun shortLe(value: Int) {
            out[offset++] = (value and 0xFF).toByte()
            out[offset++] = ((value shr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        intLe(riffSize)
        ascii("WAVE")
        ascii("fmt ")
        intLe(16)
        shortLe(1)
        shortLe(header.channels)
        intLe(header.sampleRate.toLong())
        intLe(byteRate.toLong())
        shortLe(blockAlign)
        shortLe(header.bitsPerSample)
        ascii("data")
        intLe(dataSize)
        return out
    }

    private fun findBestSidecar(dirs: List<File>, sessionId: String, suffix: String): File? {
        return dirs.asSequence()
            .mapNotNull { dir ->
                dir.listFiles { file -> file.name.contains(sessionId) && file.name.endsWith(suffix) }
                    ?.filter { it.isFile && it.length() > 0 }
                    ?.maxByOrNull { it.length() }
            }
            .firstOrNull()
    }

    private fun findBestPhotoSet(dirs: List<File>, sessionId: String): List<File> {
        return dirs.asSequence()
            .map { dir ->
                dir.listFiles { file ->
                    file.isFile && file.name.startsWith("IMG_${sessionId}_")
                }?.sortedBy { it.name } ?: emptyList()
            }
            .maxByOrNull { it.size }
            ?: emptyList()
    }

    private fun buildManifest(
        sessionId: String,
        primary: RecoveryScanner.FoundRecording,
        primarySha256: String,
        copyHashes: Map<Int, String>,
        audioExport: AudioExport,
        exportedFiles: List<ExportedFile>,
        gpsRecords: Int,
        sensorRecords: Int,
        chainRecords: Int,
        photoCount: Int,
        relatedFiles: List<File>
    ): String {
        val time = formatTime(System.currentTimeMillis())
        val audioStoppedAt = primary.file.lastModified().takeIf { it > 0 }?.let(::formatTime)
        val diagnosticsContinuedUntil = relatedFiles.maxOfOrNull { it.lastModified() }
            ?.takeIf { it > 0 }
            ?.let(::formatTime)

        return buildString {
            appendLine("{")
            appendLine("  \"session_id\": \"${json(sessionId)}\",")
            appendLine("  \"export_time\": \"${json(time)}\",")
            audioStoppedAt?.let { appendLine("  \"audio_stopped_at\": \"${json(it)}\",") }
            diagnosticsContinuedUntil?.let {
                appendLine("  \"diagnostics_continued_until\": \"${json(it)}\",")
            }
            appendLine("  \"package_format\": \"merged-readable-v1\",")
            appendLine("  \"primary_copy_index\": ${primary.locationIndex},")
            appendLine("  \"primary_copy_sha256\": \"$primarySha256\",")
            appendLine("  \"raw_copy_count\": ${copyHashes.size},")
            appendLine("  \"audio\": {")
            appendLine("    \"file\": \"${json(audioExport.file.path)}\",")
            appendLine("    \"sha256\": \"${audioExport.file.sha256}\",")
            appendLine("    \"size_bytes\": ${audioExport.file.sizeBytes},")
            appendLine("    \"sample_rate\": ${audioExport.sampleRate},")
            appendLine("    \"channels\": ${audioExport.channels},")
            appendLine("    \"bits_per_sample\": ${audioExport.bitsPerSample},")
            appendLine("    \"chunk_count\": ${audioExport.chunkCount},")
            appendLine("    \"pcm_bytes\": ${audioExport.pcmBytes}")
            appendLine("  },")
            appendLine("  \"counts\": {")
            appendLine("    \"photos\": $photoCount,")
            appendLine("    \"gps_records\": $gpsRecords,")
            appendLine("    \"sensor_records\": $sensorRecords,")
            appendLine("    \"integrity_chain_records\": $chainRecords")
            appendLine("  },")
            appendLine("  \"files\": {")
            exportedFiles.forEachIndexed { index, file ->
                val comma = if (index < exportedFiles.lastIndex) "," else ""
                appendLine(
                    "    \"${json(file.path)}\": { \"sha256\": \"${file.sha256}\", \"size_bytes\": ${file.sizeBytes} }$comma"
                )
            }
            appendLine("  },")
            appendLine("  \"raw_copies\": {")
            copyHashes.entries.toList().forEachIndexed { index, entry ->
                val comma = if (index < copyHashes.size - 1) "," else ""
                appendLine("    \"${entry.key}\": \"${entry.value}\"$comma")
            }
            appendLine("  }")
            appendLine("}")
        }
    }

    private fun buildReadme(
        sessionId: String,
        copyCount: Int,
        photoCount: Int,
        gpsRecords: Int,
        sensorRecords: Int
    ): String {
        return """
# FastLink 诊断证据包 - $sessionId

这个 ZIP 由 FastLink VPN 在设备本地导出，默认不加密。内部录制时仍使用 Android Keystore + CNCE AES-GCM 分块容器；导出时 App 已在本机完成解密、校验和合并。

## 内容

- `audio/session_$sessionId.wav`：合并后的 16-bit PCM 单声道音频，可直接播放。
- `photos/`：后台诊断图片，已按 JPG 扩展名导出，共 $photoCount 张。
- `gps/gps.csv`：定位记录，共 $gpsRecords 条，包含精度、来源、mock 标记和质量标记。
- `sensors/sensors.csv`：传感器记录，共 $sensorRecords 条。
- `integrity/integrity_chain.csv`：原始音频 chunk 的 SHA-256 连续性链，用于审计。
- `manifest.json`：导出时间、文件 SHA-256、原始副本 SHA-256、chunk 数和诊断持续时间。

## 验证

使用仓库中的 `verify/verify_evidence.py`：

```bash
python verify_evidence.py FastLink_diagnostics_$sessionId.zip
```

验证脚本会检查 manifest、WAV 结构、导出文件 SHA-256、GPS / 传感器时间连续性和哈希链连续性。

## 说明

本包只包含可交付材料，不包含内部 CNCE 容器副本和解析脚本。原始记录在设备内以 $copyCount 份副本保存，导出时选择其中一个可修复、可解密的副本生成本包。

源代码仓库：https://github.com/NoMTF/Cairn
        """.trimIndent()
    }

    private fun File.countDataRows(): Int {
        return runCatching {
            useLines(Charsets.UTF_8) { lines ->
                lines.drop(1).count { it.isNotBlank() }
            }
        }.getOrDefault(0)
    }

    private fun MessageDigest.hex(): String {
        return digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    private fun json(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    data class ExportResult(
        val success: Boolean,
        val copyCount: Int,
        val error: String?,
        val outputPath: String?
    )
}
