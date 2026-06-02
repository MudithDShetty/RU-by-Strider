package com.strider.quanto

import android.util.Log

private const val TAG = "SearchPipeline"
private const val RRF_K = 60
private const val BM25_RRF_WEIGHT = 3f

/**
 * Hybrid retrieval pipeline (BEIR / RAG standard):
 *  1. Lexical retrieve (filename, folder, content keywords)
 *  2. Granite dense retrieve (semantic — finds random filenames via indexed content sentences)
 *  3. RRF fuse both ranked lists
 *  4. Granite rerank top candidates (bi-encoder + metadata overlap)
 */
object SearchPipeline {

    fun search(
        stubs: List<IndexedFileStub>,
        enrichedQuery: EnrichedQuery,
        engine: EmbeddingEngine,
        loadEmbeddings: (List<String>) -> Map<String, FloatArray>,
        topK: Int = 10,
        semanticEnabled: Boolean = true
    ): List<SearchResult> {
        val stubMap = stubs.associateBy { it.path }

        // ── Stage 1a: Lexical ──
        val lexicalHits = LexicalSearch.search(stubs, enrichedQuery, limit = 50)
        val lexicalMap  = lexicalHits.associate { it.stub.path to it.score }

        // ── Stage 1b: Granite dense (parallel retrieval branch) ──
        val denseScored: List<Pair<String, Float>> = if (semanticEnabled) {
            denseRetrieve(enrichedQuery, stubs, lexicalHits, engine, loadEmbeddings, limit = 50)
        } else {
            emptyList()
        }

        if (lexicalHits.isEmpty() && denseScored.isEmpty()) {
            Log.w(TAG, "No hits for '${enrichedQuery.cleanQueryForEmbedding}'")
            return emptyList()
        }

        // Lexical-only fast path when semantic disabled
        if (!semanticEnabled) {
            val maxLex = lexicalHits.maxOfOrNull { it.score } ?: 1f
            return lexicalHits.take(topK).mapIndexed { rank, hit ->
                SearchResult(
                    file = stubToFile(hit.stub, loadEmbeddings),
                    score = hit.score / maxLex.coerceAtLeast(1f),
                    bm25Rank = rank,
                    denseRank = -1
                )
            }
        }

        // ── Stage 2: RRF fusion ──
        val fusedPaths = rrfFuse(lexicalHits, denseScored, topK = 25)

        val embeddings = loadEmbeddings(fusedPaths)
        val candidates = fusedPaths.mapNotNull { path ->
            val stub = stubMap[path] ?: return@mapNotNull null
            val emb  = embeddings[path] ?: return@mapNotNull null
            IndexedFile.fromStub(stub, emb)
        }

        if (candidates.isEmpty()) {
            // Fallback to lexical if embeddings missing
            val maxLex = lexicalHits.maxOf { it.score }
            return lexicalHits.take(topK).mapIndexed { rank, hit ->
                SearchResult(
                    file = stubToFile(hit.stub, loadEmbeddings),
                    score = hit.score / maxLex,
                    bm25Rank = rank,
                    denseRank = -1
                )
            }
        }

        // ── Stage 3: Granite rerank (cross-encoder stage) ──
        val lexicalRankMap = lexicalHits.mapIndexed { rank, hit -> hit.stub.path to rank }.toMap()
        val denseRankMap   = denseScored.mapIndexed { rank, (path, _) -> path to rank }.toMap()

        return GraniteReranker.rerank(
            query           = enrichedQuery,
            candidates      = candidates,
            lexicalScores   = lexicalMap,
            lexicalRankMap  = lexicalRankMap,
            denseRankMap    = denseRankMap,
            engine          = engine
        ).take(topK)
    }

    private fun denseRetrieve(
        query: EnrichedQuery,
        stubs: List<IndexedFileStub>,
        lexicalHits: List<LexicalHit>,
        engine: EmbeddingEngine,
        loadEmbeddings: (List<String>) -> Map<String, FloatArray>,
        limit: Int
    ): List<Pair<String, Float>> {
        engine.prepareForSearch()
        val queryEmb = engine.embed(query.cleanQueryForEmbedding)

        // Candidates: lexical top hits; avoid flooding entire category for specific queries
        val pathSet = linkedSetOf<String>()
        lexicalHits.take(30).forEach { pathSet.add(it.stub.path) }

        val isSpecific = query.periodHints.isNotEmpty() ||
            QueryScoring.tokenize(query.cleanQueryForEmbedding).size >= 2

        if (!isSpecific && query.categoryHints.isNotEmpty()) {
            stubs.filter { s -> query.categoryHints.any { c -> c in s.categories } }
                .take(400)
                .forEach { pathSet.add(it.path) }
        } else if (!isSpecific) {
            stubs.take(800).forEach { pathSet.add(it.path) }
        }

        val embeddings = loadEmbeddings(pathSet.toList())
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
