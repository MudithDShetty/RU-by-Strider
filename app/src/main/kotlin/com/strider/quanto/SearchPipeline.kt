package com.strider.quanto

import android.util.Log

private const val TAG = "SearchPipeline"
private const val RRF_K = 60
private const val BM25_RRF_WEIGHT = 2f

/** Reranked score below this triggers a full-library dense rescan. */
private const val LOW_CONFIDENCE_SCORE = 0.22f

/** Weak lexical-only top hit — triggers expanded pool (typing preview). */
private const val WEAK_LEXICAL_SCORE = 12f

/**
 * Hybrid retrieval pipeline (BEIR / RAG standard):
 *  1. Disk-backed candidate retrieval (FTS + SQL) → lexical score on subset
 *  2. Granite dense retrieve (embeddings loaded from SQLite for candidates only)
 *  3. RRF fuse both ranked lists
 *  4. Granite rerank top candidates
 *  5. Fallback: expanded pool + full-library dense scan when results are empty/weak
 */
object SearchPipeline {

    fun search(
        enrichedQuery: EnrichedQuery,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        topK: Int = 10,
        semanticEnabled: Boolean = true
    ): List<SearchResult> {
        val primary = runSearchPass(
            enrichedQuery = enrichedQuery,
            engine          = engine,
            db              = db,
            topK            = topK,
            semanticEnabled = semanticEnabled,
            expanded        = false,
            denseOverride   = null
        )

        if (!needsFallback(primary, semanticEnabled, db)) {
            return primary.results
        }

        Log.i(TAG, "Recall fallback triggered (${primary.reason}) — rescanning full library")

        val denseOverride = if (semanticEnabled) {
            engine.prepareForSearch()
            val queryEmb = engine.embed(enrichedQuery.cleanQueryForEmbedding)
            db.scanAllEmbeddingsTopK(queryEmb, topK = 50)
        } else {
            null
        }

        val fallback = runSearchPass(
            enrichedQuery = enrichedQuery,
            engine          = engine,
            db              = db,
            topK            = topK,
            semanticEnabled = semanticEnabled,
            expanded        = true,
            denseOverride   = denseOverride
        )

        return if (isBetterResult(fallback, primary)) {
            Log.i(TAG, "Fallback improved results (top ${fallback.results.firstOrNull()?.score ?: 0f})")
            fallback.results
        } else {
            primary.results
        }
    }

    private data class SearchPassResult(
        val results: List<SearchResult>,
        val topScore: Float,
        val lexicalCount: Int,
        val lexicalTopRaw: Float,
        val denseTop: Float,
        val reason: String = ""
    )

    private fun runSearchPass(
        enrichedQuery: EnrichedQuery,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        topK: Int,
        semanticEnabled: Boolean,
        expanded: Boolean,
        denseOverride: List<Pair<String, Float>>?
    ): SearchPassResult {
        val candidatePaths = if (expanded) {
            DiskSearch.gatherExpandedCandidatePaths(db, enrichedQuery)
        } else {
            DiskSearch.gatherCandidatePaths(db, enrichedQuery)
        }

        if (candidatePaths.isEmpty() && denseOverride.isNullOrEmpty()) {
            return SearchPassResult(emptyList(), 0f, 0, 0f, 0f, "no candidates")
        }

        val stubs = if (candidatePaths.isNotEmpty()) {
            db.loadStubsForPaths(candidatePaths)
        } else {
            emptyList()
        }
        val stubMap = stubs.associateBy { it.path }

        val lexicalHits = if (stubs.isNotEmpty()) {
            LexicalSearch.search(stubs, enrichedQuery, limit = 50)
        } else {
            emptyList()
        }
        val lexicalMap = lexicalHits.associate { it.stub.path to it.score }

        val denseScored: List<Pair<String, Float>> = when {
            denseOverride != null -> denseOverride
            semanticEnabled -> denseRetrieve(
                enrichedQuery, lexicalHits, engine, db, limit = 50, expanded = expanded
            )
            else -> emptyList()
        }

        val loadEmbeddings: (List<String>) -> Map<String, FloatArray> =
            { paths -> db.loadEmbeddingsForPaths(paths) }

        if (lexicalHits.isEmpty() && denseScored.isEmpty()) {
            return SearchPassResult(emptyList(), 0f, 0, 0f, 0f, "no hits")
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
            return SearchPassResult(
                results = results,
                topScore = results.firstOrNull()?.score ?: 0f,
                lexicalCount = lexicalHits.size,
                lexicalTopRaw = lexicalTopRaw,
                denseTop = 0f,
                reason = if (expanded) "expanded lexical" else "lexical only"
            )
        }

        val fusedPaths = rrfFuse(lexicalHits, denseScored, topK = 25)
        val fusionStubs = db.loadStubsForPaths(fusedPaths)
        val fusionStubMap = fusionStubs.associateBy { it.path }
        val embeddings = loadEmbeddings(fusedPaths)

        val candidates = fusedPaths.mapNotNull { path ->
            val stub = fusionStubMap[path] ?: stubMap[path] ?: run {
                // Fallback dense hit may be outside lexical pool — load stub on demand
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
            return SearchPassResult(
                results = results,
                topScore = results.firstOrNull()?.score ?: 0f,
                lexicalCount = lexicalHits.size,
                lexicalTopRaw = lexicalTopRaw,
                denseTop = denseScored.firstOrNull()?.second ?: 0f,
                reason = "candidates empty"
            )
        }

        val lexicalRankMap = lexicalHits.mapIndexed { rank, hit -> hit.stub.path to rank }.toMap()
        val denseRankMap = denseScored.mapIndexed { rank, (path, _) -> path to rank }.toMap()

        val results = GraniteReranker.rerank(
            query          = enrichedQuery,
            candidates     = candidates,
            lexicalScores  = lexicalMap,
            lexicalRankMap = lexicalRankMap,
            denseRankMap   = denseRankMap,
            engine         = engine
        ).take(topK)

        return SearchPassResult(
            results = results,
            topScore = results.firstOrNull()?.score ?: 0f,
            lexicalCount = lexicalHits.size,
            lexicalTopRaw = lexicalTopRaw,
            denseTop = denseScored.firstOrNull()?.second ?: 0f,
            reason = if (expanded) "expanded hybrid" else "hybrid"
        )
    }

    private fun needsFallback(
        pass: SearchPassResult,
        semanticEnabled: Boolean,
        db: DatabaseHelper
    ): Boolean {
        if (db.getTotalCount() == 0) return false

        if (pass.results.isEmpty()) return true

        if (!semanticEnabled) {
            return pass.lexicalCount == 0 || pass.lexicalTopRaw < WEAK_LEXICAL_SCORE
        }

        if (pass.topScore < LOW_CONFIDENCE_SCORE) return true
        if (pass.lexicalCount == 0 && pass.denseTop < 0.38f) return true

        return false
    }

    private fun isBetterResult(fallback: SearchPassResult, primary: SearchPassResult): Boolean {
        if (fallback.results.isEmpty()) return false
        if (primary.results.isEmpty()) return true
        return fallback.topScore > primary.topScore + 0.03f ||
            (primary.topScore < LOW_CONFIDENCE_SCORE && fallback.topScore >= primary.topScore)
    }

    private fun denseRetrieve(
        query: EnrichedQuery,
        lexicalHits: List<LexicalHit>,
        engine: EmbeddingEngine,
        db: DatabaseHelper,
        limit: Int,
        expanded: Boolean = false
    ): List<Pair<String, Float>> {
        engine.prepareForSearch()
        val queryEmb = engine.embed(query.cleanQueryForEmbedding)

        val lexicalPaths = lexicalHits.map { it.stub.path }
        val densePaths = DiskSearch.gatherDenseCandidatePaths(db, query, lexicalPaths, expanded)

        Log.d(TAG, "Dense candidate pool: ${densePaths.size} files (expanded=$expanded)")

        val embeddings = db.loadEmbeddingsForPaths(densePaths)
        if (embeddings.isEmpty()) return emptyList()

        return embeddings.map { (path, emb) ->
            path to engine.cosineSimilarity(queryEmb, emb)
        }
        .sortedByDescending { it.second }
        .take(limit)
        .also {
            Log.d(TAG, "Granite dense: ${it.size} hits, top: ${it.firstOrNull()?.first?.substringAfterLast('/')}")
        }
    }

    private fun rrfFuse(
        lexicalHits: List<LexicalHit>,
        denseScored: List<Pair<String, Float>>,
        topK: Int
    ): List<String> {
        val scores = mutableMapOf<String, Float>()

        lexicalHits.forEachIndexed { rank, hit ->
            val path = hit.stub.path
            scores[path] = (scores[path] ?: 0f) + BM25_RRF_WEIGHT / (RRF_K + rank + 1)
        }

        denseScored.forEachIndexed { rank, (path, _) ->
            scores[path] = (scores[path] ?: 0f) + 1f / (RRF_K + rank + 1)
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
