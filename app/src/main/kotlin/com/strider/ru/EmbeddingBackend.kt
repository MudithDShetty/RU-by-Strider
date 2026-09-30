package com.strider.ru

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

enum class EmbeddingBackendKind {
    INT8_NNAPI,
    INT8_CPU,
    FP32_CPU;

    val label: String get() = name.lowercase()
}

/** Selects ONNX model path and session EP flags after probe (Steps 5–5b). */
class EmbeddingBackendSelector(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingBackend"
        const val MODEL_INT8 = "embedding_model.onnx"
        const val MODEL_FP32 = "embedding_model_fp32.onnx"
        const val PREF_EMBEDDING_BACKEND = "embedding_backend_kind"
        /** Minimum FP32 size — smaller files are treated as INT8. */
        const val FP32_MIN_BYTES = 200L * 1024 * 1024
    }

    private val prefs = context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)

    fun resolveKind(forceReprobe: Boolean = false): EmbeddingBackendKind {
        if (!forceReprobe && !shouldForceReprobe()) {
            val stored = prefs.getString(PREF_EMBEDDING_BACKEND, null)
            if (stored != null) {
                val kind = runCatching { EmbeddingBackendKind.valueOf(stored) }
                    .getOrDefault(defaultKind())
                return sanitizeKind(kind)
            }
        }
        return probeAndPersist()
    }

    /** Re-run NNAPI probe after model version change or native backend crash. */
    fun shouldForceReprobe(): Boolean {
        val storedVersion = prefs.getInt(StriderApp.PREF_MODEL_VERSION, 0)
        if (storedVersion < BuildConfig.MODEL_VERSION) return true
        if (EmbeddingGuardrails.CrashHintStore.hadBackendCrash(context)) return true
        return false
    }

    fun hasFp32Model(): Boolean {
        val fp32 = File(context.filesDir, MODEL_FP32)
        return fp32.exists() && fp32.length() >= FP32_MIN_BYTES
    }

    fun modelFileFor(kind: EmbeddingBackendKind): File {
        val filesDir = context.filesDir
        val int8 = File(filesDir, MODEL_INT8)
        val fp32 = File(filesDir, MODEL_FP32)
        return when (kind) {
            EmbeddingBackendKind.FP32_CPU -> fp32
            else -> int8
        }
    }

    fun isInt8Model(file: File): Boolean = file.exists() && file.length() < FP32_MIN_BYTES

    private fun defaultKind(): EmbeddingBackendKind {
        val int8 = File(context.filesDir, MODEL_INT8)
        return when {
            int8.exists() && isInt8Model(int8) -> EmbeddingBackendKind.INT8_CPU
            hasFp32Model() -> EmbeddingBackendKind.FP32_CPU
            int8.exists() -> EmbeddingBackendKind.INT8_CPU
            else -> EmbeddingBackendKind.INT8_CPU
        }
    }

    /** Clamp stored/probed kind to models actually on disk and release NNAPI policy. */
    private fun sanitizeKind(kind: EmbeddingBackendKind): EmbeddingBackendKind {
        var resolved = kind
        var reason: String? = null

        if (resolved == EmbeddingBackendKind.INT8_NNAPI && !BuildConfig.PROBE_NNAPI) {
            resolved = EmbeddingBackendKind.INT8_CPU
            reason = "release_cpu_only"
        }

        if (resolved == EmbeddingBackendKind.FP32_CPU && !hasFp32Model()) {
            resolved = defaultKind()
            reason = "fp32_missing"
        }

        if (resolved != kind) {
            Log.i(TAG, "Sanitized backend $kind -> $resolved ($reason)")
            persist(resolved, reason, System.currentTimeMillis())
        }
        return resolved
    }

    private fun probeAndPersist(): EmbeddingBackendKind {
        val startMs = System.currentTimeMillis()
        val tracker = EmbeddingGuardrails.BackendFallbackTracker()
        var reason: String? = null

        val int8File = File(context.filesDir, MODEL_INT8)
        if (!int8File.exists()) {
            reason = "no_int8_model"
            val kind = if (hasFp32Model()) EmbeddingBackendKind.FP32_CPU else EmbeddingBackendKind.INT8_CPU
            persist(kind, reason, startMs)
            return kind
        }

        if (EmbeddingGuardrails.CrashHintStore.hadBackendCrash(context)) {
            reason = "prior_native_crash"
            val kind = if (isInt8Model(int8File)) EmbeddingBackendKind.INT8_CPU else EmbeddingBackendKind.FP32_CPU
            persist(kind, reason, startMs)
            return kind
        }

        var kind = if (isInt8Model(int8File)) EmbeddingBackendKind.INT8_CPU else EmbeddingBackendKind.FP32_CPU

        if (kind == EmbeddingBackendKind.INT8_CPU &&
            BuildConfig.PROBE_NNAPI &&
            EmbeddingGuardrails.isArm64() &&
            Build.VERSION.SDK_INT >= 29 &&
            !EmbeddingGuardrails.CrashHintStore.hadBackendCrash(context)
        ) {
            val cpuMs = measureSessionLoadMs(int8File, useNnapi = false)
            val nnapiMs = try {
                measureSessionLoadMs(int8File, useNnapi = true)
            } catch (e: Exception) {
                reason = "nnapi_error:${e.message?.take(80)}"
                Float.MAX_VALUE
            }
            if (nnapiMs < cpuMs * 1.1f) {
                kind = EmbeddingBackendKind.INT8_NNAPI
                reason = "nnapi_faster cpu=${cpuMs}ms nnapi=${nnapiMs}ms"
            } else {
                reason = "nnapi_slower cpu=${cpuMs}ms nnapi=${nnapiMs}ms"
            }
        }

        if (kind != EmbeddingBackendKind.FP32_CPU && !qualityMicroProbe(int8File, kind)) {
            if (hasFp32Model()) {
                tracker.recordEscalation()
                kind = EmbeddingBackendKind.FP32_CPU
                reason = "quality_probe_fail"
            } else {
                reason = "quality_probe_fail_no_fp32"
            }
        }

        val resolved = sanitizeKind(kind)
        persist(resolved, reason, startMs)
        return resolved
    }

    private fun measureSessionLoadMs(modelFile: File, useNnapi: Boolean): Float {
        val opts = buildSessionOptions(EmbeddingBackendKind.INT8_CPU, useNnapi)
        val env = OrtEnvironment.getEnvironment()
        val t0 = System.nanoTime()
        env.createSession(modelFile.absolutePath, opts).use { it.inputNames }
        return (System.nanoTime() - t0) / 1_000_000f
    }

    private fun qualityMicroProbe(modelFile: File, kind: EmbeddingBackendKind): Boolean {
        // Full probe runs in EmbeddingEngine after tokenizer load; here we accept INT8 if file valid
        return modelFile.length() > 1_000_000L
    }

    fun buildSessionOptions(kind: EmbeddingBackendKind, nnapiOverride: Boolean? = null): OrtSession.SessionOptions {
        val useNnapi = nnapiOverride ?: (kind == EmbeddingBackendKind.INT8_NNAPI)
        return OrtSession.SessionOptions().apply {
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (useNnapi && EmbeddingGuardrails.isArm64() && Build.VERSION.SDK_INT >= 27) {
                try {
                    addNnapi()
                } catch (e: Exception) {
                    Log.w(TAG, "addNnapi failed: ${e.message}")
                }
            }
        }
    }

    fun applyThreadCount(opts: OrtSession.SessionOptions, indexingMode: Boolean, context: Context) {
        val threads = when {
            indexingMode && EmbeddingGuardrails.ThermalGuard.useIndexingThreads(context) -> 4
            else -> 2
        }
        opts.setIntraOpNumThreads(threads)
    }

    private fun persist(kind: EmbeddingBackendKind, reason: String?, startMs: Long) {
        prefs.edit().putString(PREF_EMBEDDING_BACKEND, kind.name).apply()
        EmbeddingGuardrails.logEmbeddingProbe(
            backend = kind.label,
            probeMs = System.currentTimeMillis() - startMs,
            reason = reason
        )
        Log.i(TAG, "Selected backend=${kind.label} reason=$reason")
    }

    fun invalidateOnModelVersionChange() {
        prefs.edit().remove(PREF_EMBEDDING_BACKEND).apply()
    }
}
