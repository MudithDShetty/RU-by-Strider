package com.strider.quanto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.strider.quanto.eval.EvalLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class IndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StriderApp
        val source = inputData.getString(KEY_SOURCE) ?: "workmanager"
        if (EvalLogger.enabled) EvalLogger.logIndexWorker("started")

        // Model load can take several minutes on first install or after Android kills the process.
        if (!app.isEngineReady || !app.isCoreInitialized) {
            var waitedMs = 0L
            while ((!app.isEngineReady || !app.isCoreInitialized) && waitedMs < MAX_MODEL_WAIT_MS) {
                val secs = waitedMs / 1000
                setProgress(
                    workDataOf(
                        KEY_STATUS to "Waiting for AI model… (${secs}s)",
                        KEY_PHASE to PHASE_WAITING_MODEL
                    )
                )
                delay(WAIT_POLL_MS)
                waitedMs += WAIT_POLL_MS
            }
            if (!app.isEngineReady || !app.isCoreInitialized) {
                if (EvalLogger.enabled) {
                    EvalLogger.logIndexWorker("retry", waitForModelMs = waitedMs)
                }
                setProgress(
                    workDataOf(
                        KEY_STATUS to "AI model not ready yet — will retry",
                        KEY_PHASE to PHASE_WAITING_MODEL
                    )
                )
                return@withContext ListenableWorker.Result.retry()
            }
        }

        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        setProgress(
            workDataOf(
                KEY_STATUS to "Starting indexer…",
                KEY_PHASE to PHASE_SCANNING,
                KEY_COUNT to 0
            )
        )
        setForeground(buildForegroundInfo(0, "Starting indexer…"))

        try {
            val scanDocumentText = applicationContext.getSharedPreferences(
                StriderApp.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ).getBoolean(StriderApp.PREF_SCAN_DOCUMENT_TEXT, true)

            val result = app.indexer.indexDirectory(
                rootPath = rootPath,
                source = source,
                scanDocumentText = scanDocumentText,
                onProgress = { msg ->
                    setProgress(
                        workDataOf(
                            KEY_STATUS to msg,
                            KEY_PHASE to phaseForMessage(msg),
                            KEY_COUNT to app.indexer.size
                        )
                    )
                    setForeground(buildForegroundInfo(app.indexer.size, msg))
                },
                onFileIndexed = { indexed, _, _ ->
                    setProgress(
                        workDataOf(
                            KEY_STATUS to "Indexing files…",
                            KEY_PHASE to PHASE_INDEXING,
                            KEY_COUNT to indexed,
                            KEY_TOTAL to app.indexer.size + indexed
                        )
                    )
                    setForeground(buildForegroundInfo(indexed, "Indexing: $indexed files"))
                }
            )

            if (result.ocrPendingCount > 0 && scanDocumentText) {
                app.enqueueOcrIndexing()
            }

            if (EvalLogger.enabled) {
                EvalLogger.logIndexWorker("success", totalFiles = app.indexer.size)
            }

            setProgress(
                workDataOf(
                    KEY_STATUS to "✓ Index complete",
                    KEY_PHASE to PHASE_DONE,
                    KEY_COUNT to app.indexer.size,
                    KEY_DONE to true
                )
            )
            ListenableWorker.Result.success(
                workDataOf(
                    KEY_COUNT to app.indexer.size,
                    KEY_STATUS to "✓ Index complete",
                    KEY_PHASE to PHASE_DONE
                )
            )
        } catch (e: Exception) {
            if (EvalLogger.enabled) {
                EvalLogger.logIndexWorker("failure", error = e.message)
            }
            setProgress(
                workDataOf(
                    KEY_STATUS to "Index failed: ${e.message}",
                    KEY_PHASE to PHASE_FAILED
                )
            )
            ListenableWorker.Result.failure(
                workDataOf(KEY_STATUS to (e.message ?: "Unknown error"))
            )
        }
    }

    private fun phaseForMessage(msg: String): String = when {
        msg.contains("Scanning", ignoreCase = true) -> PHASE_SCANNING
        msg.contains("batch", ignoreCase = true) -> PHASE_INDEXING
        else -> PHASE_INDEXING
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
            .setContentTitle(applicationContext.getString(R.string.notification_indexing_title))
            .setContentText(
                applicationContext.getString(R.string.notification_indexing_body, count)
            )
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
        const val UNIQUE_WORK_NAME = "file_indexing"
        const val PERIODIC_WORK_NAME = "periodic_indexing"
        const val WORK_TAG = "indexing"

        const val KEY_STATUS = "status"
        const val KEY_COUNT = "count"
        const val KEY_TOTAL = "total"
        const val KEY_DONE = "done"
        const val KEY_FORCE_FULL = "force_full"
        const val KEY_SOURCE = "source"
        const val KEY_PHASE = "phase"

        const val PHASE_WAITING_MODEL = "waiting_model"
        const val PHASE_SCANNING = "scanning"
        const val PHASE_INDEXING = "indexing"
        const val PHASE_DONE = "done"
        const val PHASE_FAILED = "failed"

        /** Up to 15 min — first model copy+load can exceed 60s easily. */
        private const val MAX_MODEL_WAIT_MS = 15 * 60 * 1000L
        private const val WAIT_POLL_MS = 1000L
        private const val NOTIFICATION_ID = 1001

        fun inputData(forceFull: Boolean, source: String = "workmanager"): Data =
            workDataOf(KEY_FORCE_FULL to forceFull, KEY_SOURCE to source)
    }
}
