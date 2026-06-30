package com.strider.quanto

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.strider.quanto.eval.EvalLogger

private const val TAG = "EmbeddingGuardrails"

/** Runtime safety rails for embedding inference (G1–G10). */
object EmbeddingGuardrails {

    const val PREF_LAST_BACKEND_CRASHED = "embedding_backend_crashed"
    const val MAX_BACKEND_ESCALATIONS = 2
    private const val AVAIL_MEM_BATCH16 = 400L * 1024 * 1024
    private const val AVAIL_MEM_BATCH8 = 250L * 1024 * 1024

    enum class HeavyWorkState { IDLE, INDEXING, OCR, WARMING }

    @Volatile
    var heavyWorkState: HeavyWorkState = HeavyWorkState.IDLE
        private set

    val isIndexingActive: Boolean get() = heavyWorkState == HeavyWorkState.INDEXING

    fun acquireIndexing(): Boolean {
        if (heavyWorkState != HeavyWorkState.IDLE) return false
        heavyWorkState = HeavyWorkState.INDEXING
        return true
    }

    fun releaseIndexing() {
        if (heavyWorkState == HeavyWorkState.INDEXING) heavyWorkState = HeavyWorkState.IDLE
    }

    fun tryAcquireOcr(): Boolean {
        if (heavyWorkState == HeavyWorkState.INDEXING) return false
        heavyWorkState = HeavyWorkState.OCR
        return true
    }

    fun releaseOcr() {
        if (heavyWorkState == HeavyWorkState.OCR) heavyWorkState = HeavyWorkState.IDLE
    }

    fun tryAcquireWarming(): Boolean {
        if (heavyWorkState != HeavyWorkState.IDLE) return false
        heavyWorkState = HeavyWorkState.WARMING
        return true
    }

    fun releaseWarming() {
        if (heavyWorkState == HeavyWorkState.WARMING) heavyWorkState = HeavyWorkState.IDLE
    }

    object MemoryProbe {
        fun recommendedBatchSize(context: Context, default: Int = 16): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return recommendedBatchSize(default, info.availMem)
        }

        /** Threshold logic — testable without Android framework mocks. */
        internal fun recommendedBatchSize(default: Int, availMem: Long): Int = when {
            availMem < AVAIL_MEM_BATCH8 -> 4
            availMem < AVAIL_MEM_BATCH16 -> 8
            else -> default
        }
    }

    object ThermalGuard {
        fun batchCooldownMs(context: Context, batchIndex: Int): Long {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) return 40L
            return if (batchIndex > 0 && batchIndex % 4 == 0) 20L else 0L
        }

        fun useIndexingThreads(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return !pm.isPowerSaveMode
        }
    }

    class BackendFallbackTracker {
        private var escalations = 0

        fun canEscalate(): Boolean = escalations < MAX_BACKEND_ESCALATIONS

        fun recordEscalation() {
            escalations++
            Log.w(TAG, "Backend escalation $escalations/$MAX_BACKEND_ESCALATIONS")
        }

        fun reset() {
            escalations = 0
        }

        fun exhausted(): Boolean = escalations >= MAX_BACKEND_ESCALATIONS
    }

    object CrashHintStore {
        fun markBackendCrashed(context: Context) {
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_LAST_BACKEND_CRASHED, true)
                .apply()
            Log.w(TAG, "Marked embedding backend crash hint")
        }

        fun hadBackendCrash(context: Context): Boolean =
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_LAST_BACKEND_CRASHED, false)

        fun clear(context: Context) {
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_BACKEND_CRASHED)
                .apply()
        }
    }

    fun installCrashHandler(app: StriderApp) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = throwable.stackTraceToString()
            if (trace.contains("onnxruntime", ignoreCase = true) ||
                trace.contains("EmbeddingEngine", ignoreCase = true) ||
                trace.contains("OrtSession", ignoreCase = true)
            ) {
                CrashHintStore.markBackendCrashed(app)
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    fun assertNotMainThreadForEmbed() {
        if (!EvalLogger.enabled) return
        if (Looper.getMainLooper().isCurrentThread) {
            Log.e(TAG, "embed() called on main thread — ANR risk")
        }
    }

    fun isArm64(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun logEmbeddingProbe(
        backend: String,
        probeMs: Long,
        reason: String?,
        msPerFile: Float = 0f
    ) {
        if (!EvalLogger.enabled) return
        EvalLogger.logEmbeddingProbe(backend, probeMs, reason, msPerFile)
    }
}
