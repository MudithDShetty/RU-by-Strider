package com.strider.quanto

import android.util.Log
import com.strider.quanto.eval.EvalLogger

private const val TAG = "SearchPipeline"

/**
 * Hybrid retrieval pipeline (BEIR / RAG standard):
 *  1. Disk-backed candidate retrieval (FTS + SQL) → lexical score on subset
 *  2. Granite dense retrieve (embeddings loaded from SQLite for candidates only)
 *  3. RRF fuse both ranked lists
 *  4. Granite rerank top candidates
 *  5. Fallback: expanded pool + full-library dense scan when results are empty/weak
 */
object SearchPipeline {

    data class SearchOutcome(
        val results: List<SearchResult>,
        val diagnostics: SearchDiagnostics?
    )

    internal data class PassMetrics(
        val totalIndexed: Int,
        val filenamePathCount: Int,
        val contentPathCount: Int,
        val candidatePoolSize: Int,
        val lexicalCount: Int,
        val lexicalTopRaw: Float,
        val denseTopK: Int,
        val denseTopScore: Float,
        val denseTotalScanned: Int,
        val embeddingCacheWarm: Boolean,
        val fusedPoolSize: Int,
        val passReason: String,
        val expanded: Boolean,
        val topScore: Float,
        val resultCount: Int,
        val timingMs: Map<String, Long>,
        val goldenResult: SearchEval.GoldenResult? = null
    )

    fun search(
        enrichedQuery: EnrichedQuery,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        topK: Int = 10,
        semanticEnabled: Boolean = true
    ): List<SearchResult> = searchWithDiagnostics(
        enrichedQuery, engine, db, topK, semanticEnabled
    ).results

    fun searchWithDiagnostics(
        enrichedQuery: EnrichedQuery,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        topK: Int = 10,
        semanticEnabled: Boolean = true,
        trackHint: String? = null
    ): SearchOutcome {
        val searchStartNs = System.nanoTime()

        val primary = runSearchPass(
            enrichedQuery = enrichedQuery,
            engine          = engine,
            db              = db,
            topK            = topK,
            semanticEnabled = semanticEnabled,
            expanded        = false,
            denseOverride   = null,
            trackHint       = trackHint
        )

        val fallbackTriggered = needsFallback(primary, semanticEnabled, db)
        val outcome = if (!fallbackTriggered) {
            primary
        } else {
            Log.i(TAG, "Recall fallback triggered (${primary.reason}) — expanding lexical pool")

            val fallback = runSearchPass(
                enrichedQuery = enrichedQuery,
                engine          = engine,
                db              = db,
                topK            = topK,
                semanticEnabled = semanticEnabled,
                expanded        = true,
                denseOverride   = null,
                trackHint       = trackHint
            )

            if (isBetterResult(fallback, primary)) {
                Log.i(TAG, "Fallback improved results (top ${fallback.results.firstOrNull()?.score ?: 0f})")
                fallback
            } else {
                primary
            }
        }

        if (EvalLogger.enabled) {
            val metrics = outcome.metrics
            val totalMs = nsToMs(System.nanoTime() - searchStartNs)
            val timingMs = metrics.timingMs.toMutableMap().apply {
                this["total"] = totalMs
            }
            val retrieval = mapOf<String, Any?>(
                "total_indexed" to metrics.totalIndexed,
                "filename_path_count" to metrics.filenamePathCount,
                "content_path_count" to metrics.contentPathCount,
                "candidate_pool_size" to metrics.candidatePoolSize,
                "lexical_count" to metrics.lexicalCount,
                "lexical_top_raw" to metrics.lexicalTopRaw,
                "dense_top_k" to metrics.denseTopK,
                "dense_top_score" to metrics.denseTopScore,
                "dense_total_scanned" to metrics.denseTotalScanned,
                "embedding_cache_warm" to metrics.embeddingCacheWarm,
                "fused_pool_size" to metrics.fusedPoolSize,
                "pass_reason" to metrics.passReason,
                "expanded_pass" to metrics.expanded,
                "fallback_triggered" to fallbackTriggered,
                "fallback_used" to (fallbackTriggered && outcome.expanded),
                "fallback_reason" to if (fallbackTriggered) primary.reason else "",
                "top_score" to metrics.topScore,
                "result_count" to metrics.resultCount
            )
            EvalLogger.logSearch(
                EvalLogger.buildSearchSnapshot(
                    enrichedQuery = enrichedQuery,
                    semanticEnabled = semanticEnabled,
                    topK = topK,
                    trigger = EvalLogger.pendingSearchTrigger,
                    retrieval = retrieval,
                    results = outcome.results,
                    timingMs = timingMs,
                    debugTarget = outcome.diagnostics?.targetReport,
                    goldenEval = metrics.goldenResult
                )
            )
        }

        return SearchOutcome(outcome.results, outcome.diagnostics)
    }

    private data class SearchPassResult(
        val results: List<SearchResult>,
        val topScore: Float,
        val lexicalCount: Int,
        val lexicalTopRaw: Float,
        val denseTop: Float,
        val reason: String = "",
        val diagnostics: SearchDiagnostics? = null,
        val metrics: PassMetrics,
        val expanded: Boolean = false
    )

    private data class DenseRetrieveResult(
        val scored: List<Pair<String, Float>>,
        val scan: DenseScanResult,
        val denseTopK: Int,
        val queryEmbedding: FloatArray? = null
    )

    private fun runSearchPass(
        enrichedQuery: EnrichedQuery,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        topK: Int,
        semanticEnabled: Boolean,
        expanded: Boolean,
        denseOverride: List<Pair<String, Float>>?,
        trackHint: String? = null
    ): SearchPassResult {
        val startNs = System.nanoTime()
        var tAfterCandidates = startNs
        var tAfterLexical = startNs
        var tAfterDense = startNs
        var tAfterRerank = startNs

        val totalCount = db.getTotalCount()
        val filenamePaths = FilenameSearch.gatherPaths(db, enrichedQuery)
        val contentPaths = ContentSearch.gatherPaths(db, enrichedQuery)
        val candidatePaths = if (expanded) {
            DiskSearch.gatherExpandedCandidatePaths(
                db, enrichedQuery, filenamePaths, contentPaths, totalCount
            )
        } else {
            DiskSearch.gatherCandidatePaths(
                db, enrichedQuery, filenamePaths, contentPaths, totalCount
            )
        }
        tAfterCandidates = System.nanoTime()

        if (candidatePaths.isEmpty() && denseOverride.isNullOrEmpty()) {
            return emptyPassResult(
                enrichedQuery, db, expanded, "no candidates",
                totalCount, filenamePaths.size, contentPaths.size, 0,
                startNs, tAfterCandidates, tAfterLexical, tAfterDense, tAfterRerank,
                emptyList(), emptyList(), emptyList()
            )
        }

        val stubs = if (candidatePaths.isNotEmpty()) {
            db.loadStubsForPaths(candidatePaths)
        } else {
            emptyList()
        }
        val stubMap = stubs.associateBy { it.path }

        val lexicalHits = if (stubs.isNotEmpty()) {
            LexicalSearch.search(
                stubs, enrichedQuery,
                limit = RetrievalScaling.lexicalSearchLimit(totalCount)
            )
        } else {
            emptyList()
        }
        tAfterLexical = System.nanoTime()
        val lexicalMap = lexicalHits.associate { it.stub.path to it.score }

        val denseResult: DenseRetrieveResult? = when {
            denseOverride != null -> DenseRetrieveResult(
                scored = denseOverride,
                scan = DenseScanResult(denseOverride, emptyList(), db.getTotalCount()),
                denseTopK = denseOverride.size
            )
            semanticEnabled -> denseRetrieve(
                enrichedQuery, lexicalHits, engine, db,
                totalCount = totalCount, trackHint = trackHint
            )
            else -> null
        }
        tAfterDense = System.nanoTime()
        val denseScored = denseResult?.scored ?: emptyList()

        val loadEmbeddings: (List<String>) -> Map<String, FloatArray> =
            { paths -> db.loadEmbeddingsForPaths(paths) }

        if (lexicalHits.isEmpty() && denseScored.isEmpty()) {
            return emptyPassResult(
                enrichedQuery, db, expanded, "no hits",
                totalCount, filenamePaths.size, contentPaths.size, candidatePaths.size,
                startNs, tAfterCandidates, tAfterLexical, tAfterDense, tAfterRerank,
                candidatePaths, emptyList(), emptyList()
            )
        }

        val lexicalTopRaw = lexicalHits.firstOrNull()?.score ?: 0f

        if (!semanticEnabled) {
            val maxLex = lexicalHits.maxOfOrNull { it.score } ?: 1f
            val results = lexicalHits.take(topK).mapIndexed { rank, hit ->
                SearchResult(
                    file = stubToFile(hit.stub, loadEmbeddings),
                    score = hit.score / maxLex.coerceAtLeast(1f),
                    bm25Rank = rank,
                    denseRank = -1
                )
            }
            tAfterRerank = System.nanoTime()
            val reason = if (expanded) "expanded lexical" else "lexical only"
            return SearchPassResult(
                results = results,
                topScore = results.firstOrNull()?.score ?: 0f,
                lexicalCount = lexicalHits.size,
                lexicalTopRaw = lexicalTopRaw,
                denseTop = 0f,
                reason = reason,
                diagnostics = buildDiagnostics(
                    trackHint, enrichedQuery.cleanQueryForEmbedding, db, candidatePaths, lexicalHits, null,
                    emptyList(), results, semanticEnabled = false, denseTopK = 0
                ),
                metrics = buildPassMetrics(
                    db, totalCount, filenamePaths.size, contentPaths.size, candidatePaths.size,
                    lexicalHits.size, lexicalTopRaw, 0, 0f, 0,
                    fusedPoolSize = 0, passReason = reason, expanded = expanded,
                    topScore = results.firstOrNull()?.score ?: 0f, resultCount = results.size,
                    startNs, tAfterCandidates, tAfterLexical, tAfterDense, tAfterRerank,
                    goldenResult = goldenEval(enrichedQuery, candidatePaths, emptyList(), results)
                ),
                expanded = expanded
            )
        }

        val fusedPaths = buildRerankPaths(
            lexicalHits, denseScored, totalCount, filenamePaths, contentPaths
        )
        val fusionStubs = db.loadStubsForPaths(fusedPaths)
        val fusionStubMap = fusionStubs.associateBy { it.path }
        val embeddings = loadEmbeddings(fusedPaths)

        val candidates = fusedPaths.mapNotNull { path ->
            val stub = fusionStubMap[path] ?: stubMap[path] ?: run {
                db.loadStubsForPaths(listOf(path)).firstOrNull()
            } ?: return@mapNotNull null
            val emb = embeddings[path] ?: loadEmbeddings(listOf(path))[path] ?: return@mapNotNull null
            IndexedFile.fromStub(stub, emb)
        }

        if (candidates.isEmpty()) {
            val maxLex = lexicalHits.maxOfOrNull { it.score }?.coerceAtLeast(1f) ?: 1f
            val results = lexicalHits.take(topK).mapIndexed { rank, hit ->
                SearchResult(
                    file = stubToFile(hit.stub, loadEmbeddings),
                    score = hit.score / maxLex,
                    bm25Rank = rank,
                    denseRank = -1
                )
            }
            tAfterRerank = System.nanoTime()
            return SearchPassResult(
                results = results,
                topScore = results.firstOrNull()?.score ?: 0f,
                lexicalCount = lexicalHits.size,
                lexicalTopRaw = lexicalTopRaw,
                denseTop = denseScored.firstOrNull()?.second ?: 0f,
                reason = "candidates empty",
                diagnostics = buildDiagnostics(
                    trackHint, enrichedQuery.cleanQueryForEmbedding, db, candidatePaths, lexicalHits, denseResult?.scan,
                    fusedPaths, results, semanticEnabled = true,
                    denseTopK = denseResult?.denseTopK ?: 0
                ),
                metrics = buildPassMetrics(
                    db, totalCount, filenamePaths.size, contentPaths.size, candidatePaths.size,
                    lexicalHits.size, lexicalTopRaw,
                    denseResult?.denseTopK ?: 0,
                    denseScored.firstOrNull()?.second ?: 0f,
                    denseResult?.scan?.totalScanned ?: 0,
                    fusedPoolSize = fusedPaths.size, passReason = "candidates empty", expanded = expanded,
                    topScore = results.firstOrNull()?.score ?: 0f, resultCount = results.size,
                    startNs, tAfterCandidates, tAfterLexical, tAfterDense, tAfterRerank,
                    goldenResult = goldenEval(enrichedQuery, candidatePaths, fusedPaths, results)
                ),
                expanded = expanded
            )
        }

        val lexicalRankMap = lexicalHits.mapIndexed { rank, hit -> hit.stub.path to rank }.toMap()
        val denseRankMap = denseScored.mapIndexed { rank, (path, _) -> path to rank }.toMap()

        val results = GraniteReranker.takeTop(
            GraniteReranker.rerank(
                query          = enrichedQuery,
                candidates     = candidates,
                lexicalScores  = lexicalMap,
                lexicalRankMap = lexicalRankMap,
                denseRankMap   = denseRankMap,
                engine         = engine,
                queryEmbedding = denseResult?.queryEmbedding,
                denseScores    = denseScored.toMap()
            ),
            query = enrichedQuery,
            topK  = topK
        )
        tAfterRerank = System.nanoTime()

        val reason = if (expanded) "expanded hybrid" else "hybrid"
        return SearchPassResult(
            results = results,
            topScore = results.firstOrNull()?.score ?: 0f,
            lexicalCount = lexicalHits.size,
            lexicalTopRaw = lexicalTopRaw,
            denseTop = denseScored.firstOrNull()?.second ?: 0f,
            reason = reason,
            diagnostics = buildDiagnostics(
                trackHint, enrichedQuery.cleanQueryForEmbedding, db, candidatePaths, lexicalHits, denseResult?.scan,
                fusedPaths, results, semanticEnabled = true,
                denseTopK = denseResult?.denseTopK ?: 0
            ),
            metrics = buildPassMetrics(
                db, totalCount, filenamePaths.size, contentPaths.size, candidatePaths.size,
                lexicalHits.size, lexicalTopRaw,
                denseResult?.denseTopK ?: 0,
                denseScored.firstOrNull()?.second ?: 0f,
                denseResult?.scan?.totalScanned ?: 0,
                fusedPoolSize = fusedPaths.size, passReason = reason, expanded = expanded,
                topScore = results.firstOrNull()?.score ?: 0f, resultCount = results.size,
                startNs, tAfterCandidates, tAfterLexical, tAfterDense, tAfterRerank,
                goldenResult = goldenEval(enrichedQuery, candidatePaths, fusedPaths, results)
            ),
            expanded = expanded
        )
    }

    private fun emptyPassResult(
        enrichedQuery: EnrichedQuery,
        db: DatabaseHelper,
        expanded: Boolean,
        reason: String,
        totalCount: Int,
        filenameCount: Int,
        contentCount: Int,
        candidatePoolSize: Int,
        startNs: Long,
        tAfterCandidates: Long,
        tAfterLexical: Long,
        tAfterDense: Long,
        tAfterRerank: Long,
        candidatePaths: List<String>,
        fusedPaths: List<String>,
        results: List<SearchResult>
    ): SearchPassResult = SearchPassResult(
        results = results,
        topScore = 0f,
        lexicalCount = 0,
        lexicalTopRaw = 0f,
        denseTop = 0f,
        reason = reason,
        diagnostics = null,
        metrics = buildPassMetrics(
            db, totalCount, filenameCount, contentCount, candidatePoolSize,
            0, 0f, 0, 0f, 0,
            fusedPoolSize = fusedPaths.size, passReason = reason, expanded = expanded,
            topScore = 0f, resultCount = results.size,
            startNs, tAfterCandidates, tAfterLexical, tAfterDense, tAfterRerank,
            goldenResult = goldenEval(enrichedQuery, candidatePaths, fusedPaths, results)
        ),
        expanded = expanded
    )

    private fun goldenEval(
        enrichedQuery: EnrichedQuery,
        candidatePaths: List<String>,
        fusedPaths: List<String>,
        results: List<SearchResult>
    ): SearchEval.GoldenResult? = SearchEval.evaluate(
        enrichedQuery.rawQuery, candidatePaths, fusedPaths, results
    )

    private fun buildPassMetrics(
        db: DatabaseHelper,
        totalIndexed: Int,
        filenamePathCount: Int,
        contentPathCount: Int,
        candidatePoolSize: Int,
        lexicalCount: Int,
        lexicalTopRaw: Float,
        denseTopK: Int,
        denseTopScore: Float,
        denseTotalScanned: Int,
        fusedPoolSize: Int,
        passReason: String,
        expanded: Boolean,
        topScore: Float,
        resultCount: Int,
        startNs: Long,
        tAfterCandidates: Long,
        tAfterLexical: Long,
        tAfterDense: Long,
        tAfterRerank: Long,
        goldenResult: SearchEval.GoldenResult?
    ): PassMetrics = PassMetrics(
        totalIndexed = totalIndexed,
        filenamePathCount = filenamePathCount,
        contentPathCount = contentPathCount,
        candidatePoolSize = candidatePoolSize,
        lexicalCount = lexicalCount,
        lexicalTopRaw = lexicalTopRaw,
        denseTopK = denseTopK,
        denseTopScore = denseTopScore,
        denseTotalScanned = denseTotalScanned,
        embeddingCacheWarm = db.isEmbeddingCacheWarm(),
        fusedPoolSize = fusedPoolSize,
        passReason = passReason,
        expanded = expanded,
        topScore = topScore,
        resultCount = resultCount,
        timingMs = mapOf(
            "candidate_gather" to nsToMs(tAfterCandidates - startNs),
            "lexical" to nsToMs(tAfterLexical - tAfterCandidates),
            "dense" to nsToMs(tAfterDense - tAfterLexical),
            "rerank" to nsToMs(tAfterRerank - tAfterDense),
            "total" to nsToMs(tAfterRerank - startNs)
        ),
        goldenResult = goldenResult
    )

    private fun nsToMs(ns: Long): Long = ns / 1_000_000

    private fun buildDiagnostics(
        trackHint: String?,
        query: String,
        db: DatabaseHelper,
        candidatePaths: List<String>,
        lexicalHits: List<LexicalHit>,
        denseScan: DenseScanResult?,
        fusedPaths: List<String>,
        results: List<SearchResult>,
        semanticEnabled: Boolean,
        denseTopK: Int
    ): SearchDiagnostics? = SearchDiagnosticsBuilder.build(
        hint = trackHint,
        query = query,
        totalIndexed = db.getTotalCount(),
        candidatePaths = candidatePaths,
        lexicalHits = lexicalHits,
        denseScan = denseScan,
        fusedPaths = fusedPaths,
        results = results,
        semanticEnabled = semanticEnabled,
        denseTopK = denseTopK
    )

    private fun needsFallback(
        pass: SearchPassResult,
        semanticEnabled: Boolean,
        db: DatabaseHelper
    ): Boolean {
        if (db.getTotalCount() == 0) return false

        if (pass.results.isEmpty()) return true

        if (!semanticEnabled) {
            return pass.lexicalCount == 0 || pass.lexicalTopRaw < SearchWeights.WEAK_LEXICAL_SCORE
        }

        if (pass.topScore < SearchWeights.LOW_CONFIDENCE_SCORE) return true
        if (pass.lexicalCount == 0 && pass.denseTop < SearchWeights.WEAK_DENSE_SCORE) return true

        return false
    }

    private fun isBetterResult(fallback: SearchPassResult, primary: SearchPassResult): Boolean {
        if (fallback.results.isEmpty()) return false
        if (primary.results.isEmpty()) return true
        return fallback.topScore > primary.topScore + SearchWeights.FALLBACK_SCORE_MARGIN ||
            (primary.topScore < SearchWeights.LOW_CONFIDENCE_SCORE &&
                fallback.topScore >= primary.topScore)
    }

    private fun denseRetrieve(
        query: EnrichedQuery,
        lexicalHits: List<LexicalHit>,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        totalCount: Int,
        trackHint: String? = null
    ): DenseRetrieveResult {
        engine.prepareForSearch()
        val queryEmb = engine.embed(query.cleanQueryForEmbedding)
        val denseTopK = RetrievalScaling.denseTopK(totalCount)

        val hints = buildList {
            trackHint?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val scan = db.scanAllEmbeddings(queryEmb, topK = denseTopK, trackNameHints = hints)
        val scored = scan.top.toMutableList()
        val scoredPaths = scored.map { it.first }.toMutableSet()

        val pinLexical = RetrievalScaling.denseLexicalPinCount(totalCount)
        val pinPaths = lexicalHits.take(pinLexical).map { it.stub.path }.filter { it !in scoredPaths }
        if (pinPaths.isNotEmpty()) {
            db.loadEmbeddingsForPaths(pinPaths).forEach { (path, emb) ->
                scored.add(path to engine.cosineSimilarity(queryEmb, emb))
                scoredPaths.add(path)
            }
            scored.sortByDescending { it.second }
        }

        val finalScored = scored.take(denseTopK)
        Log.d(
            TAG,
            "Granite dense ($totalCount files, top-$denseTopK, cache=${db.isEmbeddingCacheWarm()}): " +
                "${finalScored.size} hits, top: ${finalScored.firstOrNull()?.first?.substringAfterLast('/')}"
        )
        return DenseRetrieveResult(finalScored, scan, denseTopK, queryEmb)
    }

    private fun buildRerankPaths(
        lexicalHits: List<LexicalHit>,
        denseScored: List<Pair<String, Float>>,
        totalCount: Int,
        filenamePaths: List<String> = emptyList(),
        contentPaths: List<String> = emptyList()
    ): List<String> {
        val fuseK = RetrievalScaling.rrfFusionTopK(totalCount)
        val pinK = RetrievalScaling.densePinCount(totalCount)
        val cap = RetrievalScaling.rerankPoolCap(totalCount)
        val exactPinLimit = RetrievalScaling.exactMatchPinLimit(totalCount)
        val lexicalPinLimit = RetrievalScaling.lexicalRerankPinLimit(totalCount)
        val fused = rrfFuse(lexicalHits, denseScored, topK = fuseK).toMutableList()
        val seen = fused.toMutableSet()
        for (path in (filenamePaths + contentPaths).distinct().take(exactPinLimit)) {
            if (seen.add(path)) fused.add(0, path)
        }
        for ((path, _) in denseScored.take(pinK)) {
            if (seen.add(path)) fused.add(path)
        }
        for (hit in lexicalHits.take(lexicalPinLimit)) {
            if (seen.add(hit.stub.path)) fused.add(hit.stub.path)
        }
        return fused.take(cap)
    }

    private fun rrfFuse(
        lexicalHits: List<LexicalHit>,
        denseScored: List<Pair<String, Float>>,
        topK: Int
    ): List<String> {
        val scores = mutableMapOf<String, Float>()

        lexicalHits.forEachIndexed { rank, hit ->
            val path = hit.stub.path
            scores[path] = (scores[path] ?: 0f) + SearchWeights.LEXICAL_RRF_WEIGHT / (SearchWeights.RRF_K + rank + 1)
        }

        denseScored.forEachIndexed { rank, (path, _) ->
            scores[path] = (scores[path] ?: 0f) + SearchWeights.DENSE_RRF_WEIGHT / (SearchWeights.RRF_K + rank + 1)
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }
    }

    private fun stubToFile(
        stub: IndexedFileStub,
        loadEmbeddings: (List<String>) -> Map<String, FloatArray>
    ): IndexedFile {
        val emb = loadEmbeddings(listOf(stub.path))[stub.path]
            ?: FloatArray(EmbeddingEngine.EMBEDDING_DIM)
        return IndexedFile.fromStub(stub, emb)
    }
}
