package com.strider.ru

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private var lastForegroundAtMs = 0L
    private var lastProgressAtMs = 0L
    private var lastForegroundCount = -1
    private val progressMutex = Mutex()

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StriderApp
        val source = inputData.getString(KEY_SOURCE) ?: "workmanager"

        // Model load can take several minutes on first install or after Android kills the process.
        if (!app.isEngineReady || !app.isCoreInitialized) {
            var waitedMs = 0L
            while ((!app.isEngineReady || !app.isCoreInitialized) && waitedMs < MAX_MODEL_WAIT_MS) {
                val secs = waitedMs / 1000
                setProgressQuiet(
                    workDataOf(
                        KEY_STATUS to "Waiting for AI model… (${secs}s)",
                        KEY_PHASE to PHASE_WAITING_MODEL
                    )
                )
                delay(WAIT_POLL_MS)
                waitedMs += WAIT_POLL_MS
            }
            if (!app.isEngineReady || !app.isCoreInitialized) {
                return@withContext ListenableWorker.Result.retry()
            }
        }

        if (!StorageAccess.canIndexStorage(applicationContext)) {
            return@withContext ListenableWorker.Result.failure(
                workDataOf(
                    KEY_STATUS to "Storage access required — grant full file access in Settings",
                    KEY_PHASE to PHASE_FAILED
                )
            )
        }

        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        setProgressQuiet(
            workDataOf(
                KEY_STATUS to "Starting scan…",
                KEY_PHASE to PHASE_SCANNING,
                KEY_COUNT to 0
            )
        )
        setForegroundSafe(buildForegroundInfo(0, "Starting scan…", PHASE_SCANNING))

        val forceFull = inputData.getBoolean(KEY_FORCE_FULL, false)

        try {
            val scanDocumentText = applicationContext.getSharedPreferences(
                StriderApp.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ).getBoolean(StriderApp.PREF_SCAN_DOCUMENT_TEXT, true)

            val result = app.indexer.indexDirectory(
                rootPath = rootPath,
                source = source,
                scanDocumentText = scanDocumentText,
                forceFull = forceFull,
                onProgress = { msg ->
                    val scanCount = Regex("(\\d+) files found").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                    val indexingTotal = Regex("Scanning (\\d+) files").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                    reportProgress(
                        count = scanCount ?: app.indexer.size,
                        status = msg,
                        phase = phaseForMessage(msg),
                        total = indexingTotal ?: 0,
                        force = true
                    )
                },
                onFileIndexed = { indexed, skipped, _ ->
                    reportProgress(
                        count = app.indexer.size,
                        status = "Scanning files… ($indexed new, $skipped unchanged)",
                        phase = PHASE_INDEXING,
                        total = indexed + skipped
                    )
                }
            )

            if (result.ocrPendingCount > 0 && scanDocumentText) {
                app.enqueueOcrIndexing()
            }

            warmEmbeddingCacheAfterIndex(app)

            if (shouldMarkEmbeddingModelSynced(forceFull, app, result)) {
                app.markEmbeddingModelSynced()
            }

            return@withContext ListenableWorker.Result.success(
                workDataOf(
                    KEY_COUNT to app.indexer.size,
                    KEY_STATUS to "✓ Scan complete",
                    KEY_PHASE to PHASE_DONE,
                    KEY_DONE to true
                )
            )
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.contains("mutex busy") == true) {
                return@withContext ListenableWorker.Result.retry()
            }
            if (e is SecurityException) {
                return@withContext ListenableWorker.Result.failure(
                    workDataOf(
                        KEY_STATUS to (e.message ?: "Storage permission required"),
                        KEY_PHASE to PHASE_FAILED
                    )
                )
            }
            val message = e.message ?: "Unknown error"
            if (runAttemptCount >= 3) {
                return@withContext ListenableWorker.Result.failure(
                    workDataOf(
                        KEY_STATUS to message,
                        KEY_PHASE to PHASE_FAILED
                    )
                )
            }
            return@withContext ListenableWorker.Result.retry()
        }
    }

    private suspend fun reportProgress(
        count: Int,
        status: String,
        phase: String,
        total: Int = 0,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressAtMs < PROGRESS_THROTTLE_MS) return
        lastProgressAtMs = now

        setProgressQuiet(
            workDataOf(
                KEY_STATUS to status,
                KEY_PHASE to phase,
                KEY_COUNT to count,
                KEY_TOTAL to total
            )
        )

        if (now - lastForegroundAtMs >= FOREGROUND_THROTTLE_MS ||
            (phase == PHASE_SCANNING && count != lastForegroundCount)
        ) {
            lastForegroundAtMs = now
            lastForegroundCount = count
            setForegroundSafe(buildForegroundInfo(count, status, phase, total))
        }
    }

    private suspend fun setProgressQuiet(data: Data) {
        progressMutex.withLock {
            setProgress(data)
        }
    }

    private fun shouldMarkEmbeddingModelSynced(
        forceFull: Boolean,
        app: StriderApp,
        result: IndexDirectoryResult
    ): Boolean {
        if (!forceFull && !app.needsEmbeddingReindex()) return false
        if (result.indexedCount > 0) return true
        if (result.skippedCount > 0) return true
        return false
    }

    private suspend fun warmEmbeddingCacheAfterIndex(app: StriderApp) {
        if (app.db.getTotalCount() < 50) return
        if (app.db.isEmbeddingCacheWarm()) return
        if (!EmbeddingGuardrails.tryAcquireWarming()) {
            app.scheduleDeferredEmbeddingWarmIfNeeded()
            return
        }
        try {
            app.db.warmEmbeddingCache()
            android.util.Log.i(TAG, "Post-index cache warm: ${app.db.isEmbeddingCacheWarm()}")
        } catch (e: OutOfMemoryError) {
            android.util.Log.w(TAG, "Post-index cache warm OOM — deferred warm will retry", e)
            app.scheduleDeferredEmbeddingWarmIfNeeded()
        } finally {
            EmbeddingGuardrails.releaseWarming()
        }
    }

    private suspend fun setForegroundSafe(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setForeground failed: ${e.message}")
        }
    }

    private fun phaseForMessage(msg: String): String = when {
        msg.contains("Scanning", ignoreCase = true) -> PHASE_SCANNING
        msg.contains("Found ", ignoreCase = true) && msg.contains("supported files", ignoreCase = true) ->
            PHASE_SCANNING
        msg.contains("up to date", ignoreCase = true) -> PHASE_DONE
        else -> PHASE_INDEXING
    }

    private fun buildForegroundInfo(
        count: Int,
        status: String,
        phase: String,
        total: Int = 0
    ): ForegroundInfo {
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyRes = when (phase) {
            PHASE_SCANNING -> R.string.notification_indexing_scanning_body
            else -> R.string.notification_indexing_body
        }
        val builder = NotificationCompat.Builder(
            applicationContext,
            StriderApp.NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(applicationContext.getString(R.string.notification_indexing_title))
            .setContentText(applicationContext.getString(bodyRes, count))
            .setSubText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)

        if (phase == PHASE_INDEXING && total > 0) {
            builder.setProgress(total, count.coerceAtMost(total), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()

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
        private const val FOREGROUND_THROTTLE_MS = 5_000L
        private const val PROGRESS_THROTTLE_MS = 5_000L
        private const val TAG = "IndexingWorker"

        fun inputData(forceFull: Boolean, source: String = "workmanager"): Data =
            workDataOf(KEY_FORCE_FULL to forceFull, KEY_SOURCE to source)
    }
}
