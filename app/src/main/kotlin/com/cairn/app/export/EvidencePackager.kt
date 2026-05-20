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
        val chainNote = if (hasChain) "- `recording/*.chain` - SHA-256 continuity chain, one row per audio chunk\n" else ""
        return """
# FastLink Diagnostic Bundle - $sessionId

This package was exported by Cairn / FastLink VPN from local device storage. The app is designed for extreme personal-safety documentation and does not use a network upload path.

## Contents

- `recording/` - encrypted CNCE audio containers and sidecar files
$chainNote- `manifest.json` - session metadata and SHA-256 hashes for $copyCount available copies
- `decrypt.py` - CNCE container inspection helper
- `README.md` - this file

## Verification

1. Use the repository script `verify/verify_evidence.py`.
2. Run:
   ```
   python verify_evidence.py <this-zip-file>
   ```
3. The script checks the manifest, primary copy hash, CNCE marker, GPS continuity where present, and hash-chain continuity where present.

## Notes

Audio is stored in device-bound Android Keystore AES-GCM CNCE containers. `decrypt.py` currently checks container structure; full off-device decryption requires the planned export-password re-encryption flow.

Source repository: https://github.com/NoMTF/Cairn
        """.trimIndent()
    }

    data class ExportResult(
        val success: Boolean,
        val copyCount: Int,
        val error: String?,
        val outputPath: String?
    )
}
