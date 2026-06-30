package com.strider.quanto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.strider.quanto.eval.EvalLogger
import java.io.File
import java.nio.LongBuffer
import kotlinx.coroutines.runBlocking

class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingEngine"
        const val EMBEDDING_DIM = 384
        const val FIXED_SEQ_LEN = 128
        const val MAX_SEQ_LEN_SHORT = 64
        const val PAD_TOKEN_ID = 179935L
        const val MODEL_ASSET = EmbeddingBackendSelector.MODEL_INT8
        private const val SESSION_REFRESH_EVERY = Int.MAX_VALUE

        private val QUALITY_PROBE_TEXTS = listOf(
            "nda quantoo agreement",
            "budget spreadsheet quarterly",
            "mera aadhaar document",
            "vacation beach photo",
            "python tutorial notes"
        )
    }

    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var sessionOpts: OrtSession.SessionOptions
    private lateinit var modelPath: String
    private lateinit var tokenizer: BpeTokenizer
    private val embedLock = Any()
    private var searchEmbedCount = 0
    private val backendSelector = EmbeddingBackendSelector(context)
    private val fallbackTracker = EmbeddingGuardrails.BackendFallbackTracker()

    /** Set by [StriderApp] to fetch on-demand FP32 before backend escalation. */
    var fp32ModelProvider: (suspend () -> Boolean)? = null

    @Volatile
    private var backendKind: EmbeddingBackendKind = EmbeddingBackendKind.FP32_CPU

    @Volatile
    private var indexingMode = false

    var isReady = false
        private set

    val currentBackend: EmbeddingBackendKind get() = backendKind

    fun initialize(
        fastPath: Boolean = false,
        forceReprobe: Boolean = false,
        onProgress: ((fraction: Float, message: String) -> Unit)? = null
    ) {
        onProgress?.invoke(0.02f, "Loading tokenizer…")
        tokenizer = BpeTokenizer(context)

        onProgress?.invoke(0.08f, "Selecting AI backend…")
        env = OrtEnvironment.getEnvironment()
        val reprobe = forceReprobe || backendSelector.shouldForceReprobe()
        backendKind = backendSelector.resolveKind(forceReprobe = reprobe)
        loadSession(backendKind)

        isReady = true
        if (fastPath) {
            onProgress?.invoke(1f, "Model ready (${backendKind.label})")
            RuLog.i(TAG) { "EmbeddingEngine ready (fast path) backend=${backendKind.label}" }
            return
        }

        onProgress?.invoke(0.5f, "Checking model quality…")
        runQualityMicroProbe()

        onProgress?.invoke(1f, "Model ready (${backendKind.label})")
        RuLog.i(TAG) { "EmbeddingEngine ready backend=${backendKind.label}" }
    }

    /** Quality probe + warmup embed — safe to run off the critical startup path on return visits. */
    fun runDeferredQualityAndWarmup(
        onProgress: ((fraction: Float, message: String) -> Unit)? = null
    ) {
        if (!isReady) return
        onProgress?.invoke(0.1f, "Checking model quality…")
        runQualityMicroProbe()
        onProgress?.invoke(0.7f, "Warming up…")
        embed("warmup", countTowardRefresh = false)
        onProgress?.invoke(1f, "Warmup complete")
        RuLog.d(TAG) { "Deferred quality/warmup finished backend=${backendKind.label}" }
    }

    fun setIndexingMode(active: Boolean) {
        if (indexingMode == active) return
        indexingMode = active
        if (!isReady) return
        synchronized(embedLock) {
            recreateSessionWithThreads()
        }
    }

    fun prepareForSearch() = synchronized(embedLock) {
        if (!isReady) return
        searchEmbedCount = 0
    }

    /** Reload ONNX session after on-demand FP32 delivery (e.g. golden eval escalation). */
    fun reinitializeBackend(kind: EmbeddingBackendKind) {
        synchronized(embedLock) {
            if (!isReady) return
            if (kind == EmbeddingBackendKind.FP32_CPU && !backendSelector.hasFp32Model()) {
                Log.w(TAG, "Cannot reinitialize FP32 — model not on disk")
                return
            }
            loadSession(kind)
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(EmbeddingBackendSelector.PREF_EMBEDDING_BACKEND, kind.name)
                .apply()
            RuLog.i(TAG) { "Reinitialized backend=${kind.label}" }
        }
    }

    fun embed(text: String, countTowardRefresh: Boolean = true): FloatArray =
        embedInternal(listOf(text), FIXED_SEQ_LEN, countTowardRefresh).first()

    fun embedBatch(
        texts: List<String>,
        maxSeqLen: Int = MAX_SEQ_LEN_SHORT,
        countTowardRefresh: Boolean = false
    ): List<FloatArray> = embedInternal(texts, maxSeqLen, countTowardRefresh)

    private fun loadSession(kind: EmbeddingBackendKind) {
        if (kind == EmbeddingBackendKind.FP32_CPU && !backendSelector.hasFp32Model()) {
            throw IllegalStateException(
                "FP32 model not available — call ensureFp32ModelReady() before loading FP32 backend"
            )
        }
        val modelFile = backendSelector.modelFileFor(kind)
        if (!modelFile.exists()) {
            throw IllegalStateException("Model not found: ${modelFile.name} — call ModelAssetDelivery.ensureModelReady() first")
        }
        modelPath = modelFile.absolutePath
        sessionOpts = backendSelector.buildSessionOptions(kind)
        backendSelector.applyThreadCount(sessionOpts, indexingMode, context)
        if (::session.isInitialized) {
            runCatching { session.close() }
        }
        session = env.createSession(modelPath, sessionOpts)
        backendKind = kind
        fallbackTracker.reset()
        RuLog.d(TAG) { "Session loaded ${modelFile.name} (${modelFile.length()} bytes) inputs=${session.inputNames}" }
    }

    private fun recreateSessionWithThreads() {
        sessionOpts = backendSelector.buildSessionOptions(backendKind)
        backendSelector.applyThreadCount(sessionOpts, indexingMode, context)
        runCatching { session.close() }
        session = env.createSession(modelPath, sessionOpts)
    }

    private fun escalateBackend(reason: String): Boolean {
        if (!fallbackTracker.canEscalate()) {
            Log.e(TAG, "Backend fallback exhausted — staying on ${backendKind.label}")
            if (EvalLogger.enabled) {
                EmbeddingGuardrails.logEmbeddingProbe(backendKind.label, 0, "fallback_exhausted:$reason")
            }
            return false
        }
        val next = when (backendKind) {
            EmbeddingBackendKind.INT8_NNAPI -> EmbeddingBackendKind.INT8_CPU
            EmbeddingBackendKind.INT8_CPU -> EmbeddingBackendKind.FP32_CPU
            EmbeddingBackendKind.FP32_CPU -> return false
        }
        if (next == EmbeddingBackendKind.FP32_CPU && !ensureFp32Local()) {
            Log.w(TAG, "FP32 model unavailable — staying on ${backendKind.label}")
            return false
        }
        fallbackTracker.recordEscalation()
        Log.w(TAG, "Escalating backend ${backendKind.label} -> ${next.label}: $reason")
        loadSession(next)
        context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(EmbeddingBackendSelector.PREF_EMBEDDING_BACKEND, next.name)
            .apply()
        return true
    }

    private fun ensureFp32Local(): Boolean {
        if (backendSelector.hasFp32Model()) return true
        val provider = fp32ModelProvider ?: return false
        if (!runBlocking { provider() }) return false
        return backendSelector.hasFp32Model()
    }

    private fun runQualityMicroProbe() {
        if (backendKind == EmbeddingBackendKind.FP32_CPU) return
        if (!backendSelector.hasFp32Model()) return

        val savedKind = backendKind
        val savedPath = modelPath
        try {
            loadSession(EmbeddingBackendKind.FP32_CPU)
            val ref = QUALITY_PROBE_TEXTS.map { embed(it, countTowardRefresh = false) }
            loadSession(savedKind)
            for (i in QUALITY_PROBE_TEXTS.indices) {
                val q = embed(QUALITY_PROBE_TEXTS[i], countTowardRefresh = false)
                val cos = cosineSimilarity(q, ref[i])
                if (cos < 0.95f) {
                    Log.w(TAG, "Quality probe fail cos=$cos text=${QUALITY_PROBE_TEXTS[i]}")
                    if (escalateBackend("quality_cos=$cos")) {
                        runQualityMicroProbe()
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Quality probe skipped: ${e.message}")
            modelPath = savedPath
        }
    }

    private fun embedInternal(
        texts: List<String>,
        maxSeqLen: Int,
        countTowardRefresh: Boolean
    ): List<FloatArray> = synchronized(embedLock) {
        EmbeddingGuardrails.assertNotMainThreadForEmbed()
        if (texts.isEmpty()) return emptyList()
        check(isReady) { "Call initialize() before embed()" }

        val sanitized = texts.map { it.trim().ifBlank { "[empty]" } }

        if (countTowardRefresh) {
            if (searchEmbedCount > 0 && searchEmbedCount % SESSION_REFRESH_EVERY == 0) {
                recreateSessionWithThreads()
            }
            searchEmbedCount += texts.size
        }

        try {
            return runInference(sanitized, maxSeqLen)
        } catch (e: OrtException) {
            Log.e(TAG, "OrtException during embed: ${e.message}")
            if (escalateBackend(e.message ?: "ort_error")) {
                return runInference(sanitized, maxSeqLen)
            }
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during embed batch=${sanitized.size}", e)
            throw e
        }
    }

    private fun runInference(sanitized: List<String>, maxSeqLen: Int): List<FloatArray> {
        val batchSize = sanitized.size
        val encoded = sanitized.map { tokenizer.encode(it.take(1000), maxSeqLen) }
        val seqLen = encoded.maxOf { it.inputIds.size }.coerceAtMost(maxSeqLen)

        val paddedIds = LongArray(batchSize * seqLen) { PAD_TOKEN_ID }
        val paddedMask = LongArray(batchSize * seqLen) { 0L }

        for ((i, enc) in encoded.withIndex()) {
            val len = minOf(enc.inputIds.size, seqLen)
            for (j in 0 until len) {
                paddedIds[i * seqLen + j] = enc.inputIds[j]
                paddedMask[i * seqLen + j] = enc.attentionMask[j]
            }
        }

        val shape = longArrayOf(batchSize.toLong(), seqLen.toLong())
        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(paddedIds), shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(paddedMask), shape)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor
        )

        val output = session.run(inputs)
        try {
            return extractBatchEmbeddings(output[0].value, paddedMask, batchSize, seqLen)
                .map { l2Normalize(it) }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            output.close()
        }
    }

    private fun extractBatchEmbeddings(
        raw: Any?,
        mask: LongArray,
        batchSize: Int,
        seqLen: Int
    ): List<FloatArray> {
        when (raw) {
            is Array<*> -> {
                if (raw.isEmpty()) throw IllegalStateException("Empty model output")
                when (raw[0]) {
                    is FloatArray -> {
                        @Suppress("UNCHECKED_CAST")
                        val batch = raw as Array<FloatArray>
                        require(batch.size == batchSize) {
                            "Expected $batchSize embeddings, got ${batch.size}"
                        }
                        return batch.toList()
                    }
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val batch = raw as Array<Array<FloatArray>>
                        return batch.indices.map { i ->
                            val rowMask = LongArray(seqLen) { j ->
                                mask.getOrElse(i * seqLen + j) { 0L }
                            }
                            meanPool(batch[i], rowMask)
                        }
                    }
                    else -> throw IllegalStateException("Unexpected batch element type")
                }
            }
        }
        throw IllegalStateException("Unexpected ONNX output type: ${raw?.javaClass?.name}")
    }

    private fun meanPool(sequence: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = sequence[0].size
        val sum = FloatArray(dim)
        var count = 0
        for (i in sequence.indices) {
            if (i < mask.size && mask[i] == 1L) {
                val tok = sequence[i]
                for (j in 0 until dim) sum[j] += tok[j]
                count++
            }
        }
        if (count == 0) return sequence[0].copyOf()
        return FloatArray(dim) { sum[it] / count }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimension mismatch" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = Math.sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 1e-8f) FloatArray(vec.size) { vec[it] / norm } else vec
    }

    fun close() {
        if (::session.isInitialized) session.close()
        isReady = false
    }
}
