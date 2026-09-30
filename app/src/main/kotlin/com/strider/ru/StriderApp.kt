package com.strider.ru

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StriderApp : Application() {

    lateinit var engine: EmbeddingEngine
        private set
    lateinit var db: DatabaseHelper
        private set
    lateinit var indexer: FileIndexer
        private set

    private val engineReadyFlag = AtomicBoolean(false)
    private val indexReadyFlag = AtomicBoolean(false)

    val isEngineReady: Boolean get() = engineReadyFlag.get()
    val isIndexReady: Boolean get() = indexReadyFlag.get()

    /** True once db, engine, and indexer objects exist (may still be loading). */
    val isCoreInitialized: Boolean
        get() = ::db.isInitialized && ::engine.isInitialized && ::indexer.isInitialized

    /** True while the embedding model is being downloaded or loaded for the first time. */
    val isFirstModelLoad: Boolean
        get() = !getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_MODEL_COPIED, false) || !isEngineReady

    val isModelSetupInProgress: Boolean
        get() {
            if (isEngineReady) return false
            val phase = currentInitState.phase
            return phase == InitPhase.MODEL_DOWNLOAD ||
                phase == InitPhase.MODEL_COPY ||
                phase == InitPhase.MODEL_LOAD ||
                phase == InitPhase.MODEL_WARMUP ||
                (phase != InitPhase.READY && isFirstModelLoad)
        }

    private var modelDelivery: ModelAssetDelivery? = null

    private val initStarted = AtomicBoolean(false)
    private val readyListeners = mutableListOf<(StriderApp) -> Unit>()
    private val indexReadyListeners = mutableListOf<(StriderApp) -> Unit>()
    private val progressListeners = mutableListOf<(AppInitState) -> Unit>()

    @Volatile
    private var currentInitState = AppInitState(InitPhase.DB, "", -1)

    fun currentInitStateOrReady(): AppInitState = currentInitState

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        RuTheme.applyStored(this)
        super.onCreate()
        instance = this
        FilePreviewLoader.init(this)
        EmbeddingGuardrails.installCrashHandler(this)
        createNotificationChannel()
        startEngineInit()
    }

    fun whenEngineReady(listener: (StriderApp) -> Unit) {
        if (isEngineReady) listener(this)
        else synchronized(readyListeners) { readyListeners.add(listener) }
    }

    fun whenIndexReady(listener: (StriderApp) -> Unit) {
        if (isIndexReady) listener(this)
        else synchronized(indexReadyListeners) { indexReadyListeners.add(listener) }
    }

    fun observeInitProgress(listener: (AppInitState) -> Unit) {
        synchronized(progressListeners) {
            listener(currentInitState)
            if (!currentInitState.isComplete) progressListeners.add(listener)
        }
    }

    private fun reportProgress(phase: InitPhase, message: String, progress: Int) {
        val state = AppInitState(phase, message, progress)
        currentInitState = state
        synchronized(progressListeners) {
            val snapshot = progressListeners.toList()
            applicationScope.launch(Dispatchers.Main) {
                snapshot.forEach { it(state) }
            }
        }
    }

    fun requestCellularDownloadConfirmation(activity: android.app.Activity) {
        modelDelivery?.showCellularConfirmation(activity)
    }

    fun retryModelDelivery() {
        initStarted.set(false)
        engineReadyFlag.set(false)
        startEngineInit()
    }

    fun needsNetworkForSetup(): Boolean {
        val delivery = modelDelivery ?: ModelAssetDelivery(this)
        return delivery.requiresNetworkDownload()
    }

    suspend fun ensureFp32ModelReady(
        onProgress: (fraction: Float, message: String) -> Unit = { _, _ -> }
    ): Boolean {
        val delivery = modelDelivery ?: ModelAssetDelivery(this).also { modelDelivery = it }
        return delivery.ensureFp32Ready(onProgress)
    }

    /** Return visit: valid INT8 on disk from a prior successful setup. */
    private fun isRepeatLaunchFastPath(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_MODEL_COPIED, false)) return false
        return (modelDelivery ?: ModelAssetDelivery(this)).isCachedInt8ModelValid()
    }

    private fun reportModelDownloadProgress(fraction: Float, message: String) {
        val pct = (3 + fraction * 42).toInt().coerceIn(3, 45)
        reportProgress(InitPhase.MODEL_DOWNLOAD, message, pct)
    }

    private fun reportModelLoadProgress(fraction: Float, message: String) {
        val pct = (45 + fraction * 25).toInt().coerceIn(45, 70)
        reportProgress(InitPhase.MODEL_LOAD, message, pct)
    }

    private fun reportModelWarmupProgress(fraction: Float, message: String) {
        val pct = (70 + fraction * 15).toInt().coerceIn(70, 85)
        reportProgress(InitPhase.MODEL_WARMUP, message, pct)
    }

    private fun reportIndexLoadProgress(message: String) {
        reportProgress(InitPhase.INDEX_LOAD, message, 90)
    }

    private fun startEngineInit() {
        if (!initStarted.compareAndSet(false, true)) return

        applicationScope.launch(Dispatchers.IO) {
            try {
                val fastPath = isRepeatLaunchFastPath()
                if (!fastPath) {
                    Log.i(TAG, "First-install init path")
                } else {
                    Log.i(TAG, "Repeat-launch fast init path")
                }

                if (!::db.isInitialized) {
                    reportProgress(InitPhase.DB, "Starting…", 2)
                    db = DatabaseHelper(this@StriderApp)
                    engine = EmbeddingEngine(this@StriderApp)
                    indexer = FileIndexer(engine, db, this@StriderApp)
                }

                // Load file index in parallel — does not need the ONNX session
                val indexJob = if (!indexReadyFlag.get()) {
                    launch {
                        val count = db.getTotalCount()
                        if (count > 0) {
                            if (!fastPath) {
                                reportIndexLoadProgress("Loading $count scanned files…")
                            }
                            indexer.loadFromDatabase(deferFtsBackfill = true)
                        }
                        indexReadyFlag.set(true)
                        Log.d(TAG, "Index ready — ${indexer.size} files")

                        synchronized(indexReadyListeners) {
                            val listeners = indexReadyListeners.toList()
                            indexReadyListeners.clear()
                            launch(Dispatchers.Main) { listeners.forEach { it(this@StriderApp) } }
                        }

                        if (count > 0) {
                            launch { indexer.runDeferredFtsBackfill() }
                            val scanDocumentText = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .getBoolean(PREF_SCAN_DOCUMENT_TEXT, true)
                            if (scanDocumentText && db.getOcrPendingCount() > 0) {
                                launch {
                                    delay(OCR_STARTUP_DEFER_MS)
                                    if (db.getOcrPendingCount() > 0) enqueueOcrIndexing()
                                }
                            }
                        }
                    }
                } else {
                    null
                }

                val delivery = ModelAssetDelivery(this@StriderApp).also { modelDelivery = it }
                val fp32Progress: (Float, String) -> Unit = { fraction, message ->
                    if (!fastPath) reportModelWarmupProgress(fraction, message)
                }
                engine.fp32ModelProvider = { ensureFp32ModelReady(fp32Progress) }

                if (delivery.requiresNetworkDownload() && !NetworkStatus.isConnected(this@StriderApp)) {
                    initStarted.set(false)
                    reportProgress(
                        InitPhase.MODEL_DOWNLOAD,
                        getString(R.string.setup_offline_warning),
                        -1
                    )
                    return@launch
                }

                delivery.ensureModelReady { fraction, message ->
                    if (fastPath) {
                        reportModelLoadProgress(0.02f, message)
                    } else {
                        reportModelDownloadProgress(fraction, message)
                    }
                }

                engine.initialize(
                    fastPath = fastPath,
                    forceReprobe = !fastPath
                ) { fraction, message ->
                    reportModelLoadProgress(fraction, message)
                }

                if (!fastPath) {
                    reportModelWarmupProgress(0.1f, "Warming up…")
                    engine.embed("warmup", countTowardRefresh = false)
                    reportModelWarmupProgress(1f, "Warmup complete")
                }

                engineReadyFlag.set(true)
                Log.d(TAG, "Engine ready backend=${engine.currentBackend.label}")

                maybeScheduleEmbeddingReindex()

                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_SETUP_COMPLETE, true)
                    .commit()

                synchronized(readyListeners) {
                    val listeners = readyListeners.toList()
                    readyListeners.clear()
                    launch(Dispatchers.Main) { listeners.forEach { it(this@StriderApp) } }
                }

                indexJob?.join()
                reportProgress(InitPhase.READY, "Ready", 100)

                scheduleDeferredEmbeddingWarmIfNeeded()

                if (fastPath) {
                    applicationScope.launch(Dispatchers.IO) {
                        engine.runDeferredQualityAndWarmup()
                    }
                }

                synchronized(progressListeners) { progressListeners.clear() }
            } catch (e: ModelDeliveryException) {
                Log.e(TAG, "Model delivery failed", e)
                initStarted.set(false)
                synchronized(progressListeners) { /* keep listeners for retry UI */ }
                reportProgress(
                    InitPhase.MODEL_DOWNLOAD,
                    e.message ?: "Model download failed — tap Retry",
                    -1
                )
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed", e)
                initStarted.set(false)
                synchronized(progressListeners) { /* keep listeners for retry UI */ }
                reportProgress(InitPhase.MODEL_LOAD, "Setup failed — tap Retry", -1)
            }
        }
    }

    fun enqueueIndexing(forceFull: Boolean = false) {
        if (!isEngineReady) {
            whenEngineReady { enqueueIndexing(forceFull) }
            return
        }
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(IndexingWorker.inputData(forceFull, source = "manual"))
            .addTag(IndexingWorker.WORK_TAG)
            .build()

        val policy = if (isMainIndexingActive()) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(this).enqueueUniqueWork(
            IndexingWorker.UNIQUE_WORK_NAME,
            policy,
            request
        )
    }

    fun enqueueOcrIndexing() {
        if (!isEngineReady) {
            whenEngineReady { enqueueOcrIndexing() }
            return
        }
        val request = OneTimeWorkRequestBuilder<OcrIndexingWorker>()
            .addTag(OcrIndexingWorker.WORK_TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            OcrIndexingWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Warm embedding RAM after index when the device is idle — keeps search fast without OOM peak. */
    fun scheduleDeferredEmbeddingWarmIfNeeded() {
        if (!::db.isInitialized) return
        if (db.isEmbeddingCacheWarm()) return
        if (db.getTotalCount() == 0) return

        applicationScope.launch(Dispatchers.IO) {
            delay(DEFERRED_WARM_DELAY_MS)
            if (db.isEmbeddingCacheWarm() || isMainIndexingActive()) return@launch
            if (!EmbeddingGuardrails.tryAcquireWarming()) return@launch
            try {
                Log.i(TAG, "Deferred embedding cache warm (${db.getTotalCount()} files)…")
                db.warmEmbeddingCache()
                Log.i(TAG, "Deferred warm finished: cache=${db.isEmbeddingCacheWarm()}")
            } finally {
                EmbeddingGuardrails.releaseWarming()
            }
        }
    }

    private fun isMainIndexingActive(): Boolean {
        val infos = WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork(IndexingWorker.UNIQUE_WORK_NAME)
            .get()
        return infos.any {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }
    }

    private fun maybeScheduleEmbeddingReindex() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val stored = prefs.getInt(PREF_EMBEDDING_MODEL_VERSION, 0)
        if (stored >= BuildConfig.MODEL_VERSION) return
        if (!StorageAccess.canIndexStorage(this)) {
            Log.i(TAG, "Embedding model version changed — deferring force re-index until storage access granted")
            prefs.edit().putBoolean(PREF_PENDING_FORCE_REINDEX, true).apply()
            return
        }
        Log.i(TAG, "Embedding model version changed ($stored → ${BuildConfig.MODEL_VERSION}) — force re-index")
        enqueueIndexing(forceFull = true)
    }

    fun consumePendingForceReindexIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_PENDING_FORCE_REINDEX, false)) return
        if (!isEngineReady || !StorageAccess.canIndexStorage(this)) return
        prefs.edit().remove(PREF_PENDING_FORCE_REINDEX).apply()
        if (needsEmbeddingReindex()) {
            Log.i(TAG, "Running deferred force re-index after storage access granted")
            enqueueIndexing(forceFull = true)
        }
    }

    fun hasPendingForceReindex(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_PENDING_FORCE_REINDEX, false)

    fun clearPendingForceReindex() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_PENDING_FORCE_REINDEX)
            .apply()
    }

    fun markEmbeddingModelSynced() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_EMBEDDING_MODEL_VERSION, BuildConfig.MODEL_VERSION)
            .apply()
    }

    fun needsEmbeddingReindex(): Boolean {
        val stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(PREF_EMBEDDING_MODEL_VERSION, 0)
        return stored < BuildConfig.MODEL_VERSION
    }

    fun schedulePeriodicIndexing(enabled: Boolean) {
        val wm = WorkManager.getInstance(this)
        if (!enabled) {
            wm.cancelUniqueWork(IndexingWorker.PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<IndexingWorker>(24, TimeUnit.HOURS)
            .setInputData(IndexingWorker.inputData(forceFull = false, source = "periodic"))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        wm.enqueueUniquePeriodicWork(
            IndexingWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_indexing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_indexing_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "StriderApp"
        const val PREFS_NAME = "strider_quanto_prefs"
        const val PREF_MODEL_COPIED = "model_copied"
        const val PREF_MODEL_VERSION = "model_version"
        const val PREF_MODEL_SHA_VERIFIED = "model_sha_verified"
        const val PREF_MODEL_FP32_SHA_VERIFIED = "model_fp32_sha_verified"
        const val PREF_EMBEDDING_MODEL_VERSION = "embedding_model_version"
        const val PREF_PENDING_FORCE_REINDEX = "pending_force_reindex"
        const val PREF_SETUP_COMPLETE = "setup_complete"
        const val PREF_SCAN_DOCUMENT_TEXT = "scan_document_text_enabled"
        const val NOTIFICATION_CHANNEL_ID = "indexing_channel"

        /** Wait after cold start before background OCR (reduces heat with model init). */
        private const val OCR_STARTUP_DEFER_MS = 90_000L

        /** Wait after index completes before loading full embedding matrix into RAM. */
        private const val DEFERRED_WARM_DELAY_MS = 45_000L

        lateinit var instance: StriderApp
            private set
    }
}
