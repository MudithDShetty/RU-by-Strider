package com.strider.quanto

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class OcrIndexingWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StriderApp

        if (!app.isEngineReady || !app.isCoreInitialized) {
            return@withContext ListenableWorker.Result.retry()
        }

        val pending = app.db.getOcrPendingCount()
        if (pending == 0) {
            return@withContext ListenableWorker.Result.success()
        }

        setForeground(buildForegroundInfo(pending, "Reading scanned documents…"))
        setProgress(workDataOf(KEY_REMAINING to pending))

        try {
            var lastForegroundAt = 0L
            val processed = app.indexer.processOcrPending(
                onProgress = { msg ->
                    setProgress(workDataOf(KEY_STATUS to msg))
                    val now = System.currentTimeMillis()
                    if (now - lastForegroundAt >= FOREGROUND_THROTTLE_MS) {
                        lastForegroundAt = now
                        val remaining = app.db.getOcrPendingCount()
                        setForeground(buildForegroundInfo(remaining, msg))
                    }
                }
            )

            val remaining = app.db.getOcrPendingCount()
            setProgress(
                workDataOf(
                    KEY_STATUS to "Scanned document reading complete",
                    KEY_REMAINING to remaining,
                    KEY_PROCESSED to processed
                )
            )

            if (remaining > 0) {
                delay(OCR_CHAIN_DEFER_MS)
                app.enqueueOcrIndexing()
            }

            ListenableWorker.Result.success(
                workDataOf(KEY_PROCESSED to processed, KEY_REMAINING to remaining)
            )
        } catch (e: Exception) {
            ListenableWorker.Result.failure(
                workDataOf(KEY_STATUS to (e.message ?: "OCR failed"))
            )
        }
    }

    private fun buildForegroundInfo(count: Int, status: String): ForegroundInfo {
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            StriderApp.NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(applicationContext.getString(R.string.notification_ocr_title))
            .setContentText(applicationContext.getString(R.string.notification_ocr_body, count))
            .setSubText(status)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "ocr_indexing"
        const val WORK_TAG = "ocr_indexing"

        const val KEY_STATUS = "status"
        const val KEY_REMAINING = "remaining"
        const val KEY_PROCESSED = "processed"

        private const val NOTIFICATION_ID = 1002
        private const val FOREGROUND_THROTTLE_MS = 5_000L
        private const val OCR_CHAIN_DEFER_MS = 30_000L
    }
}
