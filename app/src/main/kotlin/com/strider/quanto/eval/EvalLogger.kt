package com.strider.quanto.eval

import android.content.Context
import com.strider.quanto.BuildConfig
import com.strider.quanto.Category
import com.strider.quanto.EnrichedQuery
import com.strider.quanto.LanguageHint
import com.strider.quanto.OwnerIntent
import com.strider.quanto.QueryType
import com.strider.quanto.SearchEval
import com.strider.quanto.SearchResult
import com.strider.quanto.StriderApp
import com.strider.quanto.TargetFileReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Fire-and-forget eval logging to filesDir/ru_eval/. No-op when [BuildConfig.EVAL_LOGGING] is false. */
object EvalLogger {

    @JvmField
    val enabled: Boolean = BuildConfig.EVAL_LOGGING

    private var sink: EvalFileSink? = null
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var pendingSearchTrigger: String = "unknown"

    private val lastSearchQuery = AtomicReference<String?>(null)

    fun init(context: Context) {
        if (!enabled) return
        val app = context.applicationContext
        val scope = if (app is StriderApp) app.applicationScope else fallbackScope
        sink = EvalFileSink(app, scope).also { it.ensureInitialized() }
    }

    fun setSearchContext(trigger: String) {
        if (!enabled) return
        pendingSearchTrigger = trigger
    }

    fun logSearch(snapshot: SearchEvalSnapshot) {
        if (!enabled) return
        lastSearchQuery.set(snapshot.queryRaw)
        val props = linkedMapOf<String, Any?>(
            "event_id" to snapshot.eventId,
            "app_version" to BuildConfig.VERSION_NAME,
            "query" to mapOf(
                "raw" to snapshot.queryRaw,
                "clean" to snapshot.queryClean,
                "type" to snapshot.queryType.name,
                "category_hints" to snapshot.categoryHints.map { it.name },
                "time_hint" to snapshot.timeHint,
                "type_hint" to snapshot.typeHint,
                "language" to snapshot.languageHint?.name,
                "owner_intent" to snapshot.ownerIntent.name,
                "owner_target_tokens" to snapshot.ownerTargetTokens,
                "action_intent" to snapshot.actionIntent,
                "core_tokens" to snapshot.coreTokens
            ),
            "context" to mapOf(
                "trigger" to snapshot.trigger,
                "semantic_enabled" to snapshot.semanticEnabled,
                "top_k" to snapshot.topK
            ),
            "retrieval" to snapshot.retrieval,
            "results" to snapshot.results,
            "timing_ms" to snapshot.timingMs
        )
        snapshot.debugTarget?.let { props["debug_target"] = targetReportMap(it) }
        snapshot.goldenEval?.let { props["golden_eval"] = goldenEvalMap(it) }
        dispatch("search", EvalEvent.toJsonLine("search_completed", props))
    }

    fun logIndexPhase(record: IndexPhaseRecord, source: String = "manual") {
        if (!enabled) return
        val props = linkedMapOf<String, Any?>(
            "phase" to record.phase,
            "started_at" to record.startedAt,
            "ended_at" to record.endedAt,
            "duration_ms" to record.durationMs,
            "file_count" to record.fileCount,
            "source" to source
        )
        dispatch("index", EvalEvent.toJsonLine("index_phase", props))
    }

    fun logIndexPhases(phases: List<IndexPhaseRecord>, source: String = "manual") {
        if (!enabled) return
        for (record in phases) {
            logIndexPhase(record, source)
        }
    }

    fun logIndexCompleted(
        source: String,
        indexedCount: Int,
        skippedCount: Int,
        deletedCount: Int,
        totalFiles: Int,
        durationMs: Long,
        categoryCounts: Map<Category, Int>,
        phases: List<IndexPhaseRecord> = emptyList()
    ) {
        if (!enabled) return
        val props = linkedMapOf<String, Any?>(
            "source" to source,
            "indexed_count" to indexedCount,
            "skipped_count" to skippedCount,
            "deleted_count" to deletedCount,
            "total_files" to totalFiles,
            "duration_ms" to durationMs,
            "category_counts" to categoryCounts.mapKeys { it.key.name }
        )
        if (phases.isNotEmpty()) {
            props["phases"] = phases.map { it.toMap() }
        }
        dispatch("index", EvalEvent.toJsonLine("index_completed", props))
    }

    fun logIndexWorker(
        status: String,
        waitForModelMs: Long = 0,
        totalFiles: Int = 0,
        error: String? = null
    ) {
        if (!enabled) return
        val props = mapOf<String, Any?>(
            "status" to status,
            "wait_for_model_ms" to waitForModelMs,
            "total_files" to totalFiles,
            "error" to error
        )
        dispatch("index", EvalEvent.toJsonLine("index_worker", props))
    }

    fun logInitPhase(
        phase: String,
        message: String,
        progress: Int,
        durationMs: Long,
        isFirstModelLoad: Boolean = false,
        indexFileCount: Int = 0
    ) {
        if (!enabled) return
        val props = mapOf<String, Any?>(
            "phase" to phase,
            "message" to message,
            "progress" to progress,
            "duration_ms" to durationMs,
            "is_first_model_load" to isFirstModelLoad,
            "index_file_count" to indexFileCount
        )
        dispatch("init", EvalEvent.toJsonLine("init_phase", props))
    }

    fun logResultClick(
        query: String,
        clickedRank: Int,
        clickedPath: String,
        top1Path: String?
    ) {
        if (!enabled) return
        val props = mapOf<String, Any?>(
            "query" to query.ifBlank { lastSearchQuery.get() },
            "clicked_rank" to clickedRank,
            "clicked_path" to clickedPath,
            "top1_path" to top1Path
        )
        dispatch("search", EvalEvent.toJsonLine("result_clicked", props))
    }

    private fun dispatch(category: String, jsonLine: String) {
        EvalLiveFeed.record(category, jsonLine)
        sink?.append(category, jsonLine)
    }

    fun buildSearchSnapshot(
        enrichedQuery: EnrichedQuery,
        semanticEnabled: Boolean,
        topK: Int,
        trigger: String,
        retrieval: Map<String, Any?>,
        results: List<SearchResult>,
        timingMs: Map<String, Long>,
        debugTarget: TargetFileReport?,
        goldenEval: SearchEval.GoldenResult?
    ): SearchEvalSnapshot = SearchEvalSnapshot(
        eventId = UUID.randomUUID().toString().replace("-", "").take(12),
        queryRaw = enrichedQuery.rawQuery,
        queryClean = enrichedQuery.cleanQueryForEmbedding,
        queryType = enrichedQuery.queryType,
        categoryHints = enrichedQuery.categoryHints,
        timeHint = enrichedQuery.timeHint,
        typeHint = enrichedQuery.typeHint,
        languageHint = enrichedQuery.languageHint,
        ownerIntent = enrichedQuery.ownerIntent,
        ownerTargetTokens = enrichedQuery.ownerTargetTokens,
        actionIntent = enrichedQuery.actionIntent,
        coreTokens = enrichedQuery.coreTokens,
        trigger = trigger,
        semanticEnabled = semanticEnabled,
        topK = topK,
        retrieval = retrieval,
        results = results.mapIndexed { idx, r ->
            mapOf(
                "rank" to (idx + 1),
                "path" to r.file.path,
                "name" to r.file.name,
                "score" to r.score,
                "dense_rank" to r.denseRank,
                "bm25_rank" to r.bm25Rank,
                "categories" to r.file.categories.map { it.name }
            )
        },
        timingMs = timingMs,
        debugTarget = debugTarget,
        goldenEval = goldenEval
    )

    private fun targetReportMap(r: TargetFileReport): Map<String, Any?> = mapOf(
        "hint" to r.hint,
        "matched_path" to r.matchedPath,
        "matched_name" to r.matchedName,
        "dense_rank" to r.denseRank,
        "dense_score" to r.denseScore,
        "dense_top_k" to r.denseTopK,
        "lexical_rank" to r.lexicalRank,
        "lexical_score" to r.lexicalScore,
        "shown_rank" to r.shownRank,
        "in_candidate_pool" to r.inCandidatePool,
        "in_fused_pool" to r.inFusedPool,
        "semantic_enabled" to r.semanticEnabled
    )

    private fun goldenEvalMap(r: SearchEval.GoldenResult): Map<String, Any?> = mapOf(
        "query" to r.case.query,
        "expected_file_substring" to r.case.expectedFileSubstring,
        "max_shown_rank" to r.case.maxShownRank,
        "status" to if (r.allPass) "PASS" else "FAIL",
        "in_candidate_pool" to r.inCandidatePool,
        "in_rerank_pool" to r.inRerankPool,
        "shown_rank" to r.shownRank,
        "matched_path" to r.matchedPath
    )
}

data class SearchEvalSnapshot(
    val eventId: String,
    val queryRaw: String,
    val queryClean: String,
    val queryType: QueryType,
    val categoryHints: List<Category>,
    val timeHint: String?,
    val typeHint: String?,
    val languageHint: LanguageHint?,
    val ownerIntent: OwnerIntent,
    val ownerTargetTokens: List<String>,
    val actionIntent: String?,
    val coreTokens: List<String>,
    val trigger: String,
    val semanticEnabled: Boolean,
    val topK: Int,
    val retrieval: Map<String, Any?>,
    val results: List<Map<String, Any?>>,
    val timingMs: Map<String, Long>,
    val debugTarget: TargetFileReport?,
    val goldenEval: SearchEval.GoldenResult?
)
