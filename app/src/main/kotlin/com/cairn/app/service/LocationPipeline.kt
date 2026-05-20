package com.cairn.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
    private val sessionId: String
) {
    companion object {
        private const val TAG = "LocationPipeline"
        private const val INTERVAL_MS = 1000L
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

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setWaitForAccurateLocation(false)
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

        isRunning = true
        Log.i(TAG, "Location pipeline started")
    }

    /**
     * 手动写入一条空位置记录（用于 GPS 不可用时保持节奏）
     */
    fun writeNullRecord() {
        val line = "${System.currentTimeMillis()},null,null,null,null,null,null,unavailable"
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
        }

        multiWriter?.writeLine(line)
        multiWriter?.flushAndSyncAll()
    }
}
