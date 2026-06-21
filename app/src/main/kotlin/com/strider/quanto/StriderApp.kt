package com.strider.quanto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.strider.quanto.eval.EvalLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** True only on the very first install before the model file has been copied. */
    val isFirstModelLoad: Boolean
        get() = !getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_MODEL_COPIED, false)

    private val initStarted = AtomicBoolean(false)
    private val readyListeners = mutableListOf<(StriderApp) -> Unit>()
    private val indexReadyListeners = mutableListOf<(StriderApp) -> Unit>()
    private val progressListeners = mutableListOf<(AppInitState) -> Unit>()

    @Volatile
    private var currentInitState = AppInitState(InitPhase.DB, "", -1)

    private var lastInitPhase: InitPhase? = null
    private var lastInitPhaseStartMs = 0L

    fun currentInitStateOrReady(): AppInitState = currentInitState

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        instance = this
        EvalLogger.init(this)
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
        val now = System.currentTimeMillis()
        val previousPhase = lastInitPhase
        if (previousPhase != null && previousPhase != phase) {
            logInitPhaseComplete(previousPhase, currentInitState.message, currentInitState.progress, now - lastInitPhaseStartMs)
        }
        if (previousPhase != phase) {
            lastInitPhase = phase
            lastInitPhaseStartMs = now
        }

        val state = AppInitState(phase, message, progress)
        currentInitState = state
        synchronized(progressListeners) {
            val snapshot = progressListeners.toList()
            applicationScope.launch(Dispatchers.Main) {
                snapshot.forEach { it(state) }
            }
        }

        if (phase == InitPhase.READY) {
            logInitPhaseComplete(InitPhase.READY, message, progress, now - lastInitPhaseStartMs)
        }
    }

    private fun logInitPhaseComplete(phase: InitPhase, message: String, progress: Int, durationMs: Long) {
        if (!EvalLogger.enabled) return
        EvalLogger.logInitPhase(
            phase = phase.name,
            message = message,
            progress = progress,
            durationMs = durationMs,
            isFirstModelLoad = isFirstModelLoad,
            indexFileCount = if (::indexer.isInitialized) indexer.size else 0
        )
    }

    private fun startEngineInit() {
        if (!initStarted.compareAndSet(false, true)) return

        applicationScope.launch(Dispatchers.IO) {
            try {
                reportProgress(InitPhase.DB, "Starting…", 2)

                db = DatabaseHelper(this@StriderApp)
                engine = EmbeddingEngine(this@StriderApp)
                indexer = FileIndexer(engine, db)

                // Load file index in parallel — does not need the ONNX session
                val indexJob = launch {
                    val count = db.getTotalCount()
                    if (count > 0) {
                        reportProgress(InitPhase.INDEX_LOAD, "Loading $count indexed files…", 88)
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
                        launch {
                            db.warmEmbeddingCache()
                            Log.i(TAG, "Embedding RAM cache ready: ${db.isEmbeddingCacheWarm()} (${indexer.size} files)")
                        }
                    }
                }

                engine.initialize { fraction, message ->
                    val pct = (5 + fraction * 75).toInt().coerceIn(5, 80)
                    val phase = if (fraction < 0.5f) InitPhase.MODEL_COPY else InitPhase.MODEL_LOAD
                    reportProgress(phase, message, pct)
                }

                engine.embed("warmup", countTowardRefresh = false)
                engineReadyFlag.set(true)
                Log.d(TAG, "Engine ready")

                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_SETUP_COMPLETE, true)
                    .apply()

                synchronized(readyListeners) {
                    val listeners = readyListeners.toList()
                    readyListeners.clear()
                    launch(Dispatchers.Main) { listeners.forEach { it(this@StriderApp) } }
                }

                indexJob.join()
                reportProgress(InitPhase.READY, "Ready", 100)

                synchronized(progressListeners) { progressListeners.clear() }
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed", e)
                initStarted.set(false)
                reportProgress(InitPhase.MODEL_LOAD, "Setup failed — restart app", -1)
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

        WorkManager.getInstance(this).enqueueUniqueWork(
            IndexingWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
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
        const val PREF_SETUP_COMPLETE = "setup_complete"
        const val NOTIFICATION_CHANNEL_ID = "indexing_channel"

        lateinit var instance: StriderApp
            private set
    }
}
