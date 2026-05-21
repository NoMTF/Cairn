package com.cairn.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.cairn.app.storage.ParallelMultiWriter
import com.cairn.app.storage.StorageLocations
import kotlinx.coroutines.*

/**
 * 传感器采集管线。
 *
 * 每秒采集一次：
 * - 加速度计（acc_x, acc_y, acc_z）
 * - 陀螺仪（gyro_x, gyro_y, gyro_z）
 * - 磁力计（mag_x, mag_y, mag_z）
 *
 * 用途：取证链辅助证据。
 * 例如：被抢手机的瞬间，加速度计会有突然的剧烈波动 — 这本身就是事件证据。
 */
class SensorPipeline(
    private val context: Context,
    private val activeLocations: List<StorageLocations.LocationSpec>,
    private val sessionId: String,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        private const val TAG = "SensorPipeline"
        private const val DEFAULT_INTERVAL_MS = 1000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var multiWriter: ParallelMultiWriter? = null
    private var samplingJob: Job? = null
    private var isRunning = false

    // 当前读数缓存
    @Volatile private var accValues = FloatArray(3)
    @Volatile private var gyroValues = FloatArray(3)
    @Volatile private var magValues = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accValues, 0, 3)
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, gyroValues, 0, 3)
                Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magValues, 0, 3)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (isRunning) return

        multiWriter = ParallelMultiWriter(
            activeLocations, sessionId, ParallelMultiWriter.FileType.SENSOR
        )
        multiWriter?.openAll()

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // 每秒采样并写入
        samplingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                writeSample()
                delay(intervalMs)
            }
        }

        isRunning = true
        Log.i(TAG, "Sensor pipeline started")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        samplingJob?.cancel()
        samplingJob = null

        sensorManager.unregisterListener(listener)

        multiWriter?.closeAll()
        multiWriter = null

        Log.i(TAG, "Sensor pipeline stopped")
    }

    private fun writeSample() {
        val line = buildString {
            append(System.currentTimeMillis())
            append(',')
            append("%.3f".format(accValues[0]))
            append(',')
            append("%.3f".format(accValues[1]))
            append(',')
            append("%.3f".format(accValues[2]))
            append(',')
            append("%.3f".format(gyroValues[0]))
            append(',')
            append("%.3f".format(gyroValues[1]))
            append(',')
            append("%.3f".format(gyroValues[2]))
            append(',')
            append("%.3f".format(magValues[0]))
            append(',')
            append("%.3f".format(magValues[1]))
            append(',')
            append("%.3f".format(magValues[2]))
        }
        multiWriter?.writeLine(line)
        multiWriter?.flushAndSyncAll()
    }
}
