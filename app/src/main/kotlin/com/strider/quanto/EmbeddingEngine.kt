package com.strider.quanto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer

class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingEngine"
        const val EMBEDDING_DIM = 384
        /** Full sequence length for search queries (may be longer sentences). */
        const val FIXED_SEQ_LEN = 128
        /** Shorter length for filename/metadata indexing — ~60% faster inference. */
        const val MAX_SEQ_LEN_SHORT = 64
        const val PAD_TOKEN_ID = 179935L  // <|endoftext|>
        const val MODEL_ASSET = "embedding_model.onnx"
        /** Disabled — recreating the session causes heat and multi-second stalls. */
        private const val SESSION_REFRESH_EVERY = Int.MAX_VALUE
    }

    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var sessionOpts: OrtSession.SessionOptions
    private lateinit var modelPath: String
    private lateinit var tokenizer: BpeTokenizer
    private val embedLock = Any()
    private var searchEmbedCount = 0

    var isReady = false
        private set

    /**
     * @param onProgress fraction 0.0–1.0 through copy + load, plus a status message.
     */
    fun initialize(onProgress: ((fraction: Float, message: String) -> Unit)? = null) {
        onProgress?.invoke(0.02f, "Loading tokenizer…")
        Log.d(TAG, "Loading tokenizer...")
        tokenizer = BpeTokenizer(context)

        onProgress?.invoke(0.08f, "Starting AI model…")
        Log.d(TAG, "Loading ONNX model...")
        env = OrtEnvironment.getEnvironment()

        // 2 threads balances speed vs thermal load on mid-range ARM CPUs
        sessionOpts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        val modelFile = File(context.filesDir, MODEL_ASSET)
        val prefs = context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (!modelFile.exists()) {
            Log.d(TAG, "Copying model to files dir (first run only)...")
            val totalBytes = context.assets.openFd(MODEL_ASSET).length
            var copied = 0L
            context.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (totalBytes > 0) {
                            val frac = 0.08f + 0.42f * (copied.toFloat() / totalBytes)
                            onProgress?.invoke(
                                frac,
                                "Copying model… ${(copied * 100 / totalBytes).toInt()}%"
                            )
                        }
                    }
                }
            }
            prefs.edit().putBoolean(StriderApp.PREF_MODEL_COPIED, true).apply()
        } else {
            onProgress?.invoke(0.5f, "Loading AI model into memory…")
        }

        modelPath = modelFile.absolutePath
        onProgress?.invoke(0.55f, "Loading AI model into memory…")
        session = env.createSession(modelPath, sessionOpts)
        onProgress?.invoke(0.95f, "Warming up…")

        Log.d(TAG, "Model inputs:  ${session.inputNames}")
        Log.d(TAG, "Model outputs: ${session.outputNames}")

        isReady = true
        onProgress?.invoke(1f, "Model ready")
        Log.d(TAG, "EmbeddingEngine ready ✓")
    }

    /** Reset search embed counter — session stays warm to avoid reload heat. */
    fun prepareForSearch() = synchronized(embedLock) {
        if (!isReady) return
        searchEmbedCount = 0
    }

    fun embed(text: String, countTowardRefresh: Boolean = true): FloatArray =
        embedInternal(listOf(text), FIXED_SEQ_LEN, countTowardRefresh).first()

    /**
     * Batch embedding for indexing — processes up to [texts].size items in one forward pass.
     * Uses [maxSeqLen] padding; defaults to short length for filename indexing.
     */
    fun embedBatch(
        texts: List<String>,
        maxSeqLen: Int = MAX_SEQ_LEN_SHORT,
        countTowardRefresh: Boolean = false
    ): List<FloatArray> = embedInternal(texts, maxSeqLen, countTowardRefresh)

    private fun embedInternal(
        texts: List<String>,
        maxSeqLen: Int,
        countTowardRefresh: Boolean
    ): List<FloatArray> = synchronized(embedLock) {
        if (texts.isEmpty()) return emptyList()
        check(isReady) { "Call initialize() before embed()" }

        if (countTowardRefresh) {
            if (searchEmbedCount > 0 && searchEmbedCount % SESSION_REFRESH_EVERY == 0) {
                refreshSession()
            }
            searchEmbedCount += texts.size
        }

        val batchSize = texts.size
        val encoded = texts.map { tokenizer.encode(it.take(1000), maxSeqLen) }
        val seqLen = encoded.maxOf { it.inputIds.size }.coerceAtMost(maxSeqLen)

        val paddedIds  = LongArray(batchSize * seqLen) { PAD_TOKEN_ID }
        val paddedMask = LongArray(batchSize * seqLen) { 0L }

        for ((i, enc) in encoded.withIndex()) {
            val len = minOf(enc.inputIds.size, seqLen)
            for (j in 0 until len) {
                paddedIds[i * seqLen + j]  = enc.inputIds[j]
                paddedMask[i * seqLen + j] = enc.attentionMask[j]
            }
        }

        val shape = longArrayOf(batchSize.toLong(), seqLen.toLong())
        val inputIdsTensor      = OnnxTensor.createTensor(env, LongBuffer.wrap(paddedIds), shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(paddedMask), shape)

        val inputs = mapOf(
            "input_ids"      to inputIdsTensor,
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

    /** Mean pool over non-padding tokens (matches sentence embedding convention). */
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

    private fun refreshSession() {
        try {
            session.close()
        } catch (_: Exception) { }
        session = env.createSession(modelPath, sessionOpts)
        Log.d(TAG, "ONNX session refreshed")
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = Math.sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 1e-8f) FloatArray(vec.size) { vec[it] / norm } else vec
    }

    /** Called only on process termination — not from Activity.onDestroy. */
    fun close() {
        if (::session.isInitialized) session.close()
        if (::env.isInitialized) env.close()
        isReady = false
    }
}
