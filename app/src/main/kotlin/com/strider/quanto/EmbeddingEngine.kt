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
        const val MAX_SEQ_LEN = 512
        const val MODEL_ASSET = "embedding_model.onnx"
    }

    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var tokenizer: BpeTokenizer

    var isReady = false
        private set

    fun initialize() {
        Log.d(TAG, "Loading tokenizer...")
        tokenizer = BpeTokenizer(context)

        Log.d(TAG, "Loading ONNX model from assets...")
        env = OrtEnvironment.getEnvironment()

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(2)
        }

        val modelFile = File(context.cacheDir, MODEL_ASSET)
        if (!modelFile.exists()) {
            Log.d(TAG, "Copying model to cache dir (first run only)...")
            context.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8 * 1024)
                }
            }
            Log.d(TAG, "Model copied to: ${modelFile.absolutePath}")
        } else {
            Log.d(TAG, "Model already cached, skipping copy.")
        }

        session = env.createSession(modelFile.absolutePath, opts)

        Log.d(TAG, "Model inputs:  ${session.inputNames}")
        Log.d(TAG, "Model outputs: ${session.outputNames}")

        isReady = true
        Log.d(TAG, "EmbeddingEngine ready ✓")
    }

    fun embed(text: String): FloatArray {
        check(isReady) { "Call initialize() before embed()" }

        val tokens = tokenizer.encode(text.take(2000), MAX_SEQ_LEN)
        val seqLen = tokens.inputIds.size.toLong()
        val shape = longArrayOf(1, seqLen)

        val inputIdsTensor      = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens.inputIds),      shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens.attentionMask), shape)

        // Granite embedding model only takes input_ids + attention_mask (no token_type_ids)
        val inputs = mapOf(
            "input_ids"      to inputIdsTensor,
            "attention_mask" to attentionMaskTensor
        )

        val output = session.run(inputs)

        // Shape: [batch=1, seq_len, hidden=384]
        // CLS pooling: take position [0][0][:]
        val hiddenStates = output[0].value as Array<Array<FloatArray>>
        val clsVector = hiddenStates[0][0]

        inputIdsTensor.close()
        attentionMaskTensor.close()
        output.close()

        return l2Normalize(clsVector)
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

    fun close() {
        if (::session.isInitialized) session.close()
        if (::env.isInitialized) env.close()
        isReady = false
    }
}