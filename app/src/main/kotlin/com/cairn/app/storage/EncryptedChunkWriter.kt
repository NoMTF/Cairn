package com.cairn.app.storage

import com.cairn.app.crypto.CairnKeystore
import com.cairn.app.crypto.ChunkCipher
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.SecretKey

class EncryptedChunkWriter(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
    private val key: SecretKey = CairnKeystore.getOrCreateMasterKey()
) {
    private var fos: FileOutputStream? = null
    private var lenFile: File? = null
    private var chunkCount: Int = 0
    private var encryptedBytes: Long = 0
    private var closed = false

    val bytesWritten: Long get() = encryptedBytes

    fun open() {
        file.parentFile?.mkdirs()
        fos = FileOutputStream(file)
        lenFile = File(file.absolutePath + ".len")
        chunkCount = 0
        encryptedBytes = 0
        writeHeader()
    }

    fun write(plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size) {
        val chunk = plaintext.copyOfRange(offset, offset + length)
        val encrypted = ChunkCipher.encrypt(key, chunk, associatedData(chunkCount))
        val payload = encrypted.ciphertextWithTag

        val header = ByteBuffer.allocate(4 + ChunkCipher.NONCE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(payload.size - ChunkCipher.TAG_BYTES)
        header.put(encrypted.nonce)

        fos?.write(header.array())
        fos?.write(payload)

        encryptedBytes += header.capacity().toLong() + payload.size
        chunkCount++
    }

    fun flushAndSync() {
        fos?.let { stream ->
            stream.flush()
            stream.fd.sync()
        }
        lenFile?.writeText(chunkCount.toString())
    }

    fun close() {
        if (closed) return
        closed = true
        fos?.flush()
        fos?.close()
        fos = null
        fixHeader(file, chunkCount)
        lenFile?.delete()
    }

    private fun writeHeader() {
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.put(VERSION)
        header.put(byteArrayOf(0, 0, 0))
        header.putInt(sampleRate)
        header.put(channels.toByte())
        header.put(bitsPerSample.toByte())
        header.put(ALG_AES_GCM)
        header.put(0)
        header.putInt(UNKNOWN_CHUNK_COUNT)
        fos?.write(header.array())
    }

    private fun associatedData(index: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index).array()
    }

    companion object {
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte())
        private const val VERSION: Byte = 1
        private const val ALG_AES_GCM: Byte = 1
        private const val UNKNOWN_CHUNK_COUNT = -1

        fun fixHeader(containerFile: File, chunkCount: Int) {
            if (!containerFile.exists() || containerFile.length() < 20) return
            RandomAccessFile(containerFile, "rw").use { raf ->
                raf.seek(16)
                raf.writeIntLE(chunkCount)
            }
        }

        fun repairFromLenFile(containerFile: File, lenFile: File) {
            val chunks = lenFile.readText().trim().toIntOrNull() ?: return
            fixHeader(containerFile, chunks)
            lenFile.delete()
        }

        private fun RandomAccessFile.writeIntLE(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
            write((value shr 16) and 0xFF)
            write((value shr 24) and 0xFF)
        }
    }
}
