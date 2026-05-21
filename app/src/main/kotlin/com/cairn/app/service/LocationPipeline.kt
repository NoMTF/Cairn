package com.cairn.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import com.cairn.app.storage.ParallelMultiWriter
import com.cairn.app.storage.StorageLocations
import com.google.android.gms.location.*

/**
 * GPS 位置管线 — 每秒写入一条位置记录到所有副本。
 *
 * 格式：CSV sidecar 文件 <recording>.gps
 * 每行：epoch_ms,lat,lon,accuracy_m,altitude_m,bearing_deg,speed_mps,source
 *
 * 无信号时写 null,null 保持时间戳节奏不中断。
 */
class LocationPipeline(
    private val context: Context,
    private val activeLocations: List<StorageLocations.LocationSpec>,
    private val sessionId: String,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        private const val TAG = "LocationPipeline"
        private const val DEFAULT_INTERVAL_MS = 1000L
        private const val LOW_ACCURACY_THRESHOLD_M = 50f
    }

    private var fusedClient: FusedLocationProviderClient? = null
    private var multiWriter: ParallelMultiWriter? = null
    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var isRunning = false

    val lastKnownLocation: Location? get() = lastLocation

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        // 打开 GPS 多路写入器
        multiWriter = ParallelMultiWriter(
            activeLocations, sessionId, ParallelMultiWriter.FileType.GPS
        )
        multiWriter?.openAll()

        // 请求位置更新
        fusedClient = LocationServices.getFusedLocationProviderClient(context)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(true)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocation = location
                writeLocationRecord(location)
            }
        }

        fusedClient?.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )

        fusedClient?.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            ?.addOnSuccessListener { location ->
                if (location != null) {
                    lastLocation = location
                    writeLocationRecord(location)
                }
            }

        isRunning = true
        Log.i(TAG, "Location pipeline started")
    }

    /**
     * 手动写入一条空位置记录（用于 GPS 不可用时保持节奏）
     */
    fun writeNullRecord() {
        val line = "${System.currentTimeMillis()},null,null,null,null,null,null,unavailable,null,null,null,null,false,unavailable"
        multiWriter?.writeLine(line)
        multiWriter?.flushAndSyncAll()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null
        fusedClient = null

        multiWriter?.closeAll()
        multiWriter = null

        Log.i(TAG, "Location pipeline stopped")
    }

    private fun writeLocationRecord(location: Location) {
        val line = buildString {
            append(System.currentTimeMillis())
            append(',')
            append(location.latitude)
            append(',')
            append(location.longitude)
            append(',')
            append("%.1f".format(location.accuracy))
            append(',')
            append(if (location.hasAltitude()) "%.1f".format(location.altitude) else "null")
            append(',')
            append(if (location.hasBearing()) "%.1f".format(location.bearing) else "null")
            append(',')
            append(if (location.hasSpeed()) "%.2f".format(location.speed) else "null")
            append(',')
            append(if (location.provider == "gps") "gps" else "network")
            append(',')
            append(location.elapsedRealtimeNanos)
            append(',')
            append(formatVerticalAccuracy(location))
            append(',')
            append(formatBearingAccuracy(location))
            append(',')
            append(formatSpeedAccuracy(location))
            append(',')
            append(isMockLocation(location))
            append(',')
            append(if (location.accuracy > LOW_ACCURACY_THRESHOLD_M) "low_accuracy" else "ok")
        }

        multiWriter?.writeLine(line)
        multiWriter?.flushAndSyncAll()
    }

    @Suppress("DEPRECATION")
    private fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            location.isFromMockProvider
        }
    }

    private fun formatVerticalAccuracy(location: Location): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            "%.1f".format(location.verticalAccuracyMeters)
        } else {
            "null"
        }
    }

    private fun formatBearingAccuracy(location: Location): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
            "%.1f".format(location.bearingAccuracyDegrees)
        } else {
            "null"
        }
    }

    private fun formatSpeedAccuracy(location: Location): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            "%.2f".format(location.speedAccuracyMetersPerSecond)
        } else {
            "null"
        }
    }
}
