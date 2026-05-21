package com.cairn.app.storage

import com.cairn.app.crypto.CairnKeystore
import com.cairn.app.crypto.ChunkCipher
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.SecretKey

/**
 * Read-only CNCE container parser used by export.
 *
 * Recording still writes crash-safe encrypted chunks; this reader turns one
 * verified local copy back into a plaintext PCM stream inside the app sandbox.
 */
class CnceAudioReader(
    private val file: File,
    private val key: SecretKey = CairnKeystore.getOrCreateMasterKey()
) {
    data class Header(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val chunkCount: Int,
        val pcmBytes: Long,
        val actualChunks: Int
    )

    fun inspect(): Header {
        RandomAccessFile(file, "r").use { raf ->
            return readHeaderAndScan(raf)
        }
    }

    fun decryptChunks(onChunk: (ByteArray) -> Unit): Header {
        RandomAccessFile(file, "r").use { raf ->
            val header = readHeaderAndScan(raf)
            raf.seek(HEADER_BYTES.toLong())

            repeat(header.actualChunks) { index ->
                val ciphertextLength = raf.readIntLE()
                val nonce = ByteArray(ChunkCipher.NONCE_BYTES)
                raf.readFully(nonce)
                val payload = ByteArray(ciphertextLength + ChunkCipher.TAG_BYTES)
                raf.readFully(payload)

                val plaintext = ChunkCipher.decrypt(
                    key = key,
                    nonce = nonce,
                    ciphertextWithTag = payload,
                    associatedData = associatedData(index)
                )
                onChunk(plaintext)
            }
            return header
        }
    }

    private fun readHeaderAndScan(raf: RandomAccessFile): Header {
        if (raf.length() < HEADER_BYTES) {
            throw IllegalArgumentException("CNCE file too small: ${file.name}")
        }

        val magic = ByteArray(4)
        raf.seek(0)
        raf.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Not a CNCE container: ${file.name}" }

        val version = raf.readUnsignedByte()
        require(version == VERSION) { "Unsupported CNCE version $version" }

        raf.skipBytes(3)
        val sampleRate = raf.readIntLE()
        val channels = raf.readUnsignedByte()
        val bitsPerSample = raf.readUnsignedByte()
        val algorithm = raf.readUnsignedByte()
        require(algorithm == ALG_AES_GCM) { "Unsupported CNCE algorithm $algorithm" }
        raf.skipBytes(1)
        val declaredChunks = raf.readIntLE()

        var offset = HEADER_BYTES.toLong()
        var actualChunks = 0
        var pcmBytes = 0L
        val fileLength = raf.length()
        while (offset < fileLength) {
            if (fileLength - offset < CHUNK_PREFIX_BYTES) {
                throw IllegalArgumentException("Truncated CNCE chunk header at offset $offset")
            }
            raf.seek(offset)
            val ciphertextLength = raf.readIntLE()
            require(ciphertextLength >= 0) { "Invalid CNCE chunk length $ciphertextLength" }

            val chunkSize = CHUNK_PREFIX_BYTES + ciphertextLength + ChunkCipher.TAG_BYTES.toLong()
            if (offset + chunkSize > fileLength) {
                throw IllegalArgumentException("Truncated CNCE chunk payload at offset $offset")
            }
            pcmBytes += ciphertextLength.toLong()
            actualChunks++
            offset += chunkSize
        }

        if (declaredChunks >= 0 && declaredChunks != actualChunks) {
            throw IllegalArgumentException("CNCE chunk count mismatch: header=$declaredChunks actual=$actualChunks")
        }

        return Header(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            chunkCount = declaredChunks,
            pcmBytes = pcmBytes,
            actualChunks = actualChunks
        )
    }

    private fun associatedData(index: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index).array()
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 or b1 or b2 or b3 < 0) {
            throw IllegalArgumentException("Unexpected EOF in CNCE integer")
        }
        return (b0 and 0xFF) or
            ((b1 and 0xFF) shl 8) or
            ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }

    companion object {
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte())
        private const val VERSION = 1
        private const val ALG_AES_GCM = 1
        private const val HEADER_BYTES = 20
        private const val CHUNK_PREFIX_BYTES = 4L + ChunkCipher.NONCE_BYTES
    }
}
