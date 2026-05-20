package com.cairn.app.storage

import java.security.MessageDigest

/**
 * 哈希链完整性验证。
 *
 * 每 1 秒 chunk 结尾附带元数据：
 * - 上一 chunk 的 SHA-256
 * - 当前 chunk 时间戳
 * - 设备指纹
 *
 * 类似区块链结构：任何 chunk 被篡改或删除，后续链全部断裂。
 * 用于法庭证据不可否认性验证。
 */
class IntegrityChain(private val deviceFingerprint: String) {

    private var prevChunkHash: String = "0000000000000000000000000000000000000000000000000000000000000000"
    private val digest = MessageDigest.getInstance("SHA-256")

    /**
     * 处理一个 chunk 并生成哈希链条目
     */
    fun processChunk(chunkData: ByteArray, timestampMs: Long): ChainEntry {
        // 计算当前 chunk 的 hash = SHA256(prevHash + data + timestamp + deviceId)
        digest.reset()
        digest.update(prevChunkHash.toByteArray())
        digest.update(chunkData)
        digest.update(timestampMs.toString().toByteArray())
        digest.update(deviceFingerprint.toByteArray())

        val currentHash = digest.digest().joinToString("") { "%02x".format(it) }

        val entry = ChainEntry(
            chunkIndex = chunkIndex,
            timestampMs = timestampMs,
            prevHash = prevChunkHash,
            currentHash = currentHash,
            dataSize = chunkData.size,
            deviceFingerprint = deviceFingerprint
        )

        prevChunkHash = currentHash
        chunkIndex++

        return entry
    }

    private var chunkIndex: Long = 0

    /**
     * 验证一条链是否完整（用于 verify_evidence.py 的 Kotlin 版参考实现）
     */
    companion object {
        fun verifyChain(entries: List<ChainEntry>): VerifyResult {
            if (entries.isEmpty()) return VerifyResult(true, 0, null)

            for (i in 1 until entries.size) {
                if (entries[i].prevHash != entries[i - 1].currentHash) {
                    return VerifyResult(false, i.toLong(), "Chain broken at chunk $i")
                }
            }
            return VerifyResult(true, entries.size.toLong(), null)
        }
    }

    data class ChainEntry(
        val chunkIndex: Long,
        val timestampMs: Long,
        val prevHash: String,
        val currentHash: String,
        val dataSize: Int,
        val deviceFingerprint: String
    ) {
        fun toCsvLine(): String {
            return "$chunkIndex,$timestampMs,$prevHash,$currentHash,$dataSize,$deviceFingerprint"
        }

        companion object {
            const val CSV_HEADER = "chunk_index,timestamp_ms,prev_hash,current_hash,data_size,device_fingerprint"
        }
    }

    data class VerifyResult(
        val valid: Boolean,
        val verifiedChunks: Long,
        val error: String?
    )
}
