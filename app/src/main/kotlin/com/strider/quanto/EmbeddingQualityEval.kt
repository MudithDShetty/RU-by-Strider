package com.strider.quanto

import android.util.Log
import com.strider.quanto.eval.EvalLogger

private const val TAG = "EmbeddingQualityEval"

/** Post-index quality gate — golden queries must PASS or schedule FP32 re-index. */
object EmbeddingQualityEval {

    suspend fun runIfNeeded(app: StriderApp) {
        if (!EvalLogger.enabled) return

        for (case in SearchEval.GOLDEN_CASES) {
            val enriched = enrichQuery(case.query)
            val outcome = SearchPipeline.searchWithDiagnostics(
                enrichedQuery = enriched,
                engine = app.engine,
                db = app.db,
                semanticEnabled = true
            )
            val golden = SearchEval.evaluate(
                case.query,
                emptyList(),
                emptyList(),
                outcome.results
            ) ?: continue
            if (!golden.rankPass && case.query.equals("nda quantoo", ignoreCase = true)) {
                Log.e(TAG, "Golden FAIL for \"${case.query}\" on ${app.engine.currentBackend.label}")
                scheduleFp32Reindex(app)
                return
            }
        }
        Log.i(TAG, "Golden spot-check done on ${app.engine.currentBackend.label}")
    }

    private suspend fun scheduleFp32Reindex(app: StriderApp) {
        val fp32Ready = app.ensureFp32ModelReady { _, message ->
            Log.i(TAG, "FP32 fetch: $message")
        }
        if (!fp32Ready) {
            Log.e(TAG, "Golden eval failed but FP32 model unavailable — staying on ${app.engine.currentBackend.label}")
            return
        }
        app.getSharedPreferences(StriderApp.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(EmbeddingBackendSelector.PREF_EMBEDDING_BACKEND, EmbeddingBackendKind.FP32_CPU.name)
            .apply()
        EvalLogger.logEmbeddingProbe(
            backend = EmbeddingBackendKind.FP32_CPU.label,
            probeMs = 0,
            reason = "quality_eval_fail"
        )
        app.engine.reinitializeBackend(EmbeddingBackendKind.FP32_CPU)
        app.enqueueIndexing(forceFull = true)
    }
}
