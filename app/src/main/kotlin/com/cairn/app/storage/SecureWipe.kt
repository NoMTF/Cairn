package com.cairn.app.storage

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

object SecureWipe {

    private const val TAG = "SecureWipe"
    private const val BUFFER_SIZE = 8192
    private val random = SecureRandom()
    private val sessionPattern = Regex("\\d{8}_\\d{6}")

    fun wipeFile(file: File): Boolean {
        if (!file.exists()) return true

        var raf: RandomAccessFile? = null
        return try {
            val fileSize = file.length()
            if (fileSize > 0L) {
                raf = RandomAccessFile(file, "rw")
                overwriteWith(raf, fileSize, 0x00.toByte())
                overwriteWith(raf, fileSize, 0xFF.toByte())
                overwriteWithRandom(raf, fileSize)
            }
            raf?.close()
            raf = null
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Wipe failed: ${file.absolutePath}", e)
            try {
                raf?.close()
            } catch (_: Exception) {
            }
            file.delete()
        }
    }

    fun wipeLocation(spec: StorageLocations.LocationSpec): Int {
        val dir = File(spec.dirPath)
        if (!dir.exists()) return 0

        var wiped = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && isCairnOwnedFile(file, spec)) {
                if (wipeFile(file)) wiped++
            }
        }
        dir.delete()
        return wiped
    }

    fun wipeAllLocations(locations: List<StorageLocations.LocationSpec>): Int {
        var total = 0
        for (spec in locations) {
            total += wipeLocation(spec)
        }
        Log.i(TAG, "Total wiped: $total files")
        return total
    }

    private fun isCairnOwnedFile(file: File, spec: StorageLocations.LocationSpec): Boolean {
        val name = file.name
        if (name == ".nomedia") return true

        val baseName = name.removeSuffix(".len")
        val session = sessionPattern.find(baseName)?.value ?: return false

        val audio = StorageLocations.generateFileName(spec, session)
        val gps = StorageLocations.generateGpsFileName(spec, session)
        val chain = StorageLocations.generateChainFileName(spec, session)
        val sensor = "$audio.sensor"

        return baseName == audio ||
            baseName == gps ||
            baseName == chain ||
            baseName == sensor ||
            baseName.startsWith("IMG_${session}_")
    }

    private fun overwriteWith(raf: RandomAccessFile, size: Long, value: Byte) {
        raf.seek(0)
        val buffer = ByteArray(BUFFER_SIZE) { value }
        var remaining = size
        while (remaining > 0) {
            val toWrite = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
            raf.write(buffer, 0, toWrite)
            remaining -= toWrite
        }
        raf.fd.sync()
    }

    private fun overwriteWithRandom(raf: RandomAccessFile, size: Long) {
        raf.seek(0)
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            random.nextBytes(buffer)
            val toWrite = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
            raf.write(buffer, 0, toWrite)
            remaining -= toWrite
        }
        raf.fd.sync()
    }
}
