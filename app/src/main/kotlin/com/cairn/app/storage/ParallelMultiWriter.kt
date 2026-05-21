package com.cairn.app.storage

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * N 路并行写入器。
 *
 * 同一份 PCM 数据同时写入 N 个目标文件，每秒 fsync。
 * 任何单路失败不影响其他路 — 哪怕 9 路都坏了，1 路活就够。
 *
 * 用于音频、GPS sidecar、照片等所有需要多副本保护的文件。
 */
class ParallelMultiWriter(
    private val locations: List<StorageLocations.LocationSpec>,
    private val sessionId: String,
    private val fileType: FileType = FileType.AUDIO,
    private val sampleRate: Int = 16000
) {
    enum class FileType { AUDIO, GPS, SENSOR, CHAIN }

    private val writers = CopyOnWriteArrayList<WriterEntry>()
    private val tag = "ParallelMultiWriter"

    data class WriterEntry(
        val locationIndex: Int,
        val file: File,
        val chunkWriter: EncryptedChunkWriter? = null,
        val fos: FileOutputStream? = null,
        var alive: Boolean = true,
        var error: String? = null
    )

    /**
     * 打开所有写入路径
     */
    fun openAll() {
        for (spec in locations) {
            try {
                val dir = StorageLocations.ensureDirectory(spec)
                when (fileType) {
                    FileType.AUDIO -> {
                        val fileName = StorageLocations.generateFileName(spec, sessionId)
                        val file = File(dir, fileName)
                        val writer = EncryptedChunkWriter(file, sampleRate = sampleRate)
                        writer.open()
                        writers.add(WriterEntry(spec.index, file, chunkWriter = writer))
                    }
                    FileType.GPS -> {
                        val fileName = StorageLocations.generateGpsFileName(spec, sessionId)
                        val file = File(dir, fileName)
                        val fos = FileOutputStream(file)
                        // 写 CSV header
                        val header = "epoch_ms,lat,lon,accuracy_m,altitude_m,bearing_deg,speed_mps,source," +
                            "elapsed_realtime_nanos,vertical_accuracy_m,bearing_accuracy_deg," +
                            "speed_accuracy_mps,is_mock,quality\n"
                        fos.write(header.toByteArray())
                        writers.add(WriterEntry(spec.index, file, fos = fos))
                    }
                    FileType.SENSOR -> {
                        val fileName = "${StorageLocations.generateFileName(spec, sessionId)}.sensor"
                        val file = File(dir, fileName)
                        val fos = FileOutputStream(file)
                        fos.write("epoch_ms,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z\n".toByteArray())
                        writers.add(WriterEntry(spec.index, file, fos = fos))
                    }
                    FileType.CHAIN -> {
                        val fileName = StorageLocations.generateChainFileName(spec, sessionId)
                        val file = File(dir, fileName)
                        val fos = FileOutputStream(file)
                        fos.write("${IntegrityChain.ChainEntry.CSV_HEADER}\n".toByteArray())
                        writers.add(WriterEntry(spec.index, file, fos = fos))
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to open location ${spec.index}: ${e.message}")
                // 单路失败不影响其他
            }
        }
        Log.i(tag, "Opened ${writers.count { it.alive }} / ${locations.size} writers for $fileType")
    }

    /**
     * 写入 PCM 数据到所有存活的音频写入器
     */
    fun writeAudio(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size) {
        for (entry in writers) {
            if (!entry.alive) continue
            try {
                entry.chunkWriter?.write(pcmData, offset, length)
            } catch (e: Exception) {
                markDead(entry, e)
            }
        }
    }

    /**
     * 写入一行文本到所有存活的 GPS/Sensor 写入器
     */
    fun writeLine(line: String) {
        val bytes = "$line\n".toByteArray()
        for (entry in writers) {
            if (!entry.alive) continue
            try {
                entry.fos?.write(bytes)
            } catch (e: Exception) {
                markDead(entry, e)
            }
        }
    }

    /**
     * 每秒调用 — 强制 fsync 所有存活的写入器
     */
    fun flushAndSyncAll() {
        for (entry in writers) {
            if (!entry.alive) continue
            try {
                when (fileType) {
                    FileType.AUDIO -> entry.chunkWriter?.flushAndSync()
                    FileType.GPS, FileType.SENSOR, FileType.CHAIN -> {
                        entry.fos?.flush()
                        entry.fos?.fd?.sync()
                    }
                }
            } catch (e: Exception) {
                markDead(entry, e)
            }
        }
    }

    /**
     * 正常关闭所有
     */
    fun closeAll() {
        for (entry in writers) {
            try {
                entry.chunkWriter?.close()
                entry.fos?.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing ${entry.locationIndex}: ${e.message}")
            }
        }
        writers.clear()
    }

    /**
     * 获取存活路数
     */
    fun aliveCount(): Int = writers.count { it.alive }

    /**
     * 获取全部路数
     */
    fun totalCount(): Int = writers.size

    /**
     * 获取已写字节数（取第一个存活路的数据）
     */
    fun bytesWritten(): Long {
        return writers.firstOrNull { it.alive }?.chunkWriter?.bytesWritten ?: 0
    }

    private fun markDead(entry: WriterEntry, e: Exception) {
        entry.alive = false
        entry.error = e.message
        Log.w(tag, "Writer ${entry.locationIndex} died: ${e.message}")
    }
}
