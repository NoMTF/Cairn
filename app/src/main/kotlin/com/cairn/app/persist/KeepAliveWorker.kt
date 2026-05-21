package com.cairn.app.persist

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cairn.app.service.EvidenceDiagnosticsService
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class KeepAliveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        val desiredAudio = settings.desiredAudioActiveFlow.first()
        val desiredDiagnostics = settings.desiredDiagnosticsActiveFlow.first()
        val sessionId = settings.lastSessionIdFlow.first()

        if (desiredAudio && !RecordingService.isRunning) {
            RecordingService.start(applicationContext)
        }
        if (desiredDiagnostics && !EvidenceDiagnosticsService.isRunning) {
            EvidenceDiagnosticsService.start(applicationContext, sessionId)
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "fastlink_keep_alive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
