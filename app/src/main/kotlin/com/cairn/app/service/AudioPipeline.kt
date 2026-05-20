package com.cairn.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.cairn.app.storage.IntegrityChain
import com.cairn.app.storage.ParallelMultiWriter
import com.cairn.app.storage.StorageLocations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioPipeline(
    private val context: Context,
    private val activeLocations: List<StorageLocations.LocationSpec>,
    private val deviceFingerprint: String,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val onStatusUpdate: (AudioStatus) -> Unit = {}
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_MS = 100
        const val FRAMES_PER_CHUNK = 10

        private const val TAG = "AudioPipeline"
    }

    data class AudioStatus(
        val recording: Boolean,
        val durationMs: Long,
        val aliveWriters: Int,
        val totalWriters: Int,
        val bytesWritten: Long
    )

    private var audioRecord: AudioRecord? = null
    private var multiWriter: ParallelMultiWriter? = null
    private var chainWriter: ParallelMultiWriter? = null
    private var integrityChain: IntegrityChain? = null

    private var recordingJob: Job? = null
    private var isRecording = false
    private var startTimeMs: Long = 0
    val sessionId: String = generateSessionId()

    val isActive: Boolean get() = isRecording

    val durationMs: Long
        get() = if (isRecording) System.currentTimeMillis() - startTimeMs else 0

    private val bufferSamples: Int get() = sampleRate * FRAME_SIZE_MS / 1000
    private val bufferBytes: Int get() = bufferSamples * 2

    fun start(): Boolean {
        if (isRecording) return false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing RECORD_AUDIO permission")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "Unsupported AudioRecord config: sampleRate=$sampleRate, minBufferSize=$minBufferSize")
            return false
        }
        val bufferSize = maxOf(minBufferSize, bufferBytes * 4)

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord permission revoked", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        multiWriter = ParallelMultiWriter(
            activeLocations, sessionId, ParallelMultiWriter.FileType.AUDIO, sampleRate
        )
        multiWriter?.openAll()

        chainWriter = ParallelMultiWriter(
            activeLocations, sessionId, ParallelMultiWriter.FileType.CHAIN, sampleRate
        )
        chainWriter?.openAll()

        integrityChain = IntegrityChain(deviceFingerprint)

        try {
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord start denied", e)
            releaseResources()
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord start failed", e)
            releaseResources()
            return false
        }

        isRecording = true
        startTimeMs = System.currentTimeMillis()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            recordingLoop()
        }

        Log.i(
            TAG,
            "Recording started: session=$sessionId, writers=${multiWriter?.aliveCount()}/${multiWriter?.totalCount()}"
        )
        return true
    }

    suspend fun stopAndJoin() {
        stop()
        recordingJob?.cancelAndJoin()
        recordingJob = null
    }

    fun stop() {
        if (!isRecording && audioRecord == null && multiWriter == null && chainWriter == null) return
        isRecording = false

        recordingJob?.cancel()

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        releaseResources()

        Log.i(TAG, "Recording stopped: session=$sessionId, duration=${durationMs}ms")
    }

    private suspend fun recordingLoop() {
        val frameBuffer = ByteArray(bufferBytes)
        val chunkBuffer = ByteArray(bufferBytes * FRAMES_PER_CHUNK)
        var chunkOffset = 0
        var frameCount = 0

        while (isRecording) {
            val readResult = try {
                audioRecord?.read(frameBuffer, 0, bufferBytes) ?: break
            } catch (e: SecurityException) {
                Log.e(TAG, "AudioRecord read denied", e)
                break
            }

            if (readResult > 0) {
                if (chunkOffset + readResult > chunkBuffer.size) {
                    writeChunk(chunkBuffer, chunkOffset)
                    chunkOffset = 0
                    frameCount = 0
                }
                System.arraycopy(frameBuffer, 0, chunkBuffer, chunkOffset, readResult)
                chunkOffset += readResult
                frameCount++

                if (frameCount >= FRAMES_PER_CHUNK) {
                    writeChunk(chunkBuffer, chunkOffset)
                    chunkOffset = 0
                    frameCount = 0

                    onStatusUpdate(
                        AudioStatus(
                            recording = true,
                            durationMs = durationMs,
                            aliveWriters = multiWriter?.aliveCount() ?: 0,
                            totalWriters = multiWriter?.totalCount() ?: 0,
                            bytesWritten = multiWriter?.bytesWritten() ?: 0
                        )
                    )
                }
            } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION ||
                readResult == AudioRecord.ERROR_BAD_VALUE
            ) {
                Log.e(TAG, "AudioRecord read error: $readResult")
                break
            }

            yield()
        }

        if (chunkOffset > 0) {
            writeChunk(chunkBuffer, chunkOffset)
        }
    }

    private fun writeChunk(data: ByteArray, length: Int) {
        if (length <= 0) return

        multiWriter?.writeAudio(data, 0, length)
        multiWriter?.flushAndSyncAll()

        val chunkData = data.copyOf(length)
        val entry = integrityChain?.processChunk(chunkData, System.currentTimeMillis())
        if (entry != null) {
            chainWriter?.writeLine(entry.toCsvLine())
            chainWriter?.flushAndSyncAll()
        }
    }

    private fun releaseResources() {
        audioRecord?.release()
        audioRecord = null

        multiWriter?.closeAll()
        multiWriter = null

        chainWriter?.closeAll()
        chainWriter = null
    }

    private fun generateSessionId(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return sdf.format(Date())
    }
}
