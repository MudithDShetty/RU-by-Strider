package com.strider.ru

import android.util.Log

private const val TAG = "GraniteReranker"

/**
 * Second-stage reranker using Granite bi-encoder + token overlap on metadata.
 */
object GraniteReranker {

    fun rerank(
        query: EnrichedQuery,
        candidates: List<IndexedFile>,
        lexicalScores: Map<String, Float>,
        lexicalRankMap: Map<String, Int>,
        denseRankMap: Map<String, Int>,
        engine: EmbeddingEngine,
        queryEmbedding: FloatArray? = null,
        denseScores: Map<String, Float>? = null
    ): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()

        val queryEmb = queryEmbedding ?: run {
            engine.prepareForSearch()
            engine.embed(query.cleanQueryForEmbedding)
        }
        val maxLex = lexicalScores.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val queryTokens = if (query.lexicalTokens.isNotEmpty()) {
            query.lexicalTokens
        } else {
            query.cleanQueryForEmbedding.lowercase()
                .split(Regex("\\s+"))
                .filter { it.length > 1 }
        }
        val coreTokens = query.coreTokens.ifEmpty {
            MultilingualBridge.coreTokens(query.cleanQueryForEmbedding)
        }

        val results = candidates.map { file ->
            val dense = denseScores?.get(file.path)
                ?: engine.cosineSimilarity(queryEmb, file.embedding)
            val lexNorm = (lexicalScores[file.path] ?: 0f) / maxLex
            val nameNorm = QueryScoring.filenameStem(file.name)
            val filenameQueryMatch = QueryScoring.queryMatchesFilenameStem(nameNorm, coreTokens)

            val (lexW, denseW) = when {
                filenameQueryMatch ->
                    SearchWeights.RERANK_FILENAME_QUERY_LEX to SearchWeights.RERANK_FILENAME_QUERY_DENSE
                query.queryType == QueryType.PERSON_NAME ->
                    SearchWeights.RERANK_PERSON_LEX to SearchWeights.RERANK_PERSON_DENSE
                query.periodHints.isNotEmpty() && lexNorm > SearchWeights.RERANK_PERIOD_LEX_NORM_MIN ->
                    SearchWeights.RERANK_PERIOD_LEX to SearchWeights.RERANK_PERIOD_DENSE
                lexNorm > SearchWeights.RERANK_STRONG_LEX_NORM_MIN ->
                    SearchWeights.RERANK_STRONG_LEX to SearchWeights.RERANK_STRONG_DENSE
                lexNorm < SearchWeights.RERANK_WEAK_LEX_NORM_MAX ->
                    SearchWeights.RERANK_WEAK_LEX to SearchWeights.RERANK_WEAK_DENSE
                else ->
                    SearchWeights.RERANK_DEFAULT_LEX to SearchWeights.RERANK_DEFAULT_DENSE
            }

            val meta = file.metadata.metadataString.lowercase()
            val content = file.metadata.contentSnippet?.lowercase() ?: ""
            val entities = file.metadata.extractedEntities.joinToString(" ").lowercase()

            val overlapHits = queryTokens.count { t ->
                QueryScoring.tokenMatchesInFields(nameNorm, meta, content, entities, t)
            }
            val overlap = overlapHits.toFloat() / coreTokens.size.coerceAtLeast(1)

            val entityHits = coreTokens.count { t ->
                QueryScoring.textContainsToken(entities, t)
            }
            val entityCoverage = entityHits.toFloat() / coreTokens.size.coerceAtLeast(1)

            val nameTokens = QueryScoring.stemTokens(nameNorm)
            val nameCoverage = QueryScoring.filenameCoverageRatio(nameTokens, coreTokens)
            val allTokensInName = QueryScoring.filenameCoversAllTokens(nameNorm, coreTokens)

            var score = lexNorm * lexW + dense * denseW +
                overlap * SearchWeights.RERANK_OVERLAP_WEIGHT +
                nameCoverage * SearchWeights.RERANK_NAME_COVERAGE_WEIGHT
            if (filenameQueryMatch) score += SearchWeights.RERANK_FILENAME_QUERY_BONUS
            if (allTokensInName) score += SearchWeights.RERANK_ALL_TOKENS_IN_NAME_BONUS
            if (entityCoverage >= 1f) score += SearchWeights.RERANK_ENTITY_FULL_BONUS
            else if (entityCoverage > 0f) score += entityCoverage * SearchWeights.RERANK_ENTITY_PARTIAL_MULT
            if (query.queryType == QueryType.PERSON_NAME && entityCoverage > 0f) {
                score += SearchWeights.RERANK_PERSON_ENTITY_BONUS
            }

            val denseRank = denseRankMap[file.path]
            score += denseRankBonus(lexNorm, denseRank, dense)

            val langMult = MultilingualBridge.languageHintMultiplier(
                query.languageHint, file.name, meta, content
            )
            val categoryMult = if (query.categoryHints.any { it in file.categories }) {
                SearchWeights.CATEGORY_MATCH_MULT
            } else {
                1f
            }
            val timeMult = if (query.timeHint != null && file.metadata.ageBucket == query.timeHint) {
                SearchWeights.TIME_BUCKET_MULT
            } else {
                1f
            }
            val specificityMult = QueryScoring.specificityMultiplier(
                nameNorm, meta, content, coreTokens, query.periodHints, entities
            )
            val ownerMult = OwnerMatcher.scoreMultiplier(
                file.metadata.ownerEntities,
                file.metadata.ownerConfidence,
                query.ownerIntent,
                query.ownerTargetTokens
            )

            score = QueryScoring.applyMultiplierChain(
                score, langMult, categoryMult, timeMult, specificityMult, ownerMult
            ).coerceIn(0f, 1f)
            SearchResult(
                file            = file,
                score           = score,
                denseRank       = denseRankMap[file.path] ?: -1,
                bm25Rank        = lexicalRankMap[file.path] ?: -1,
                categoryMatched = query.categoryHints.any { it in file.categories }
            )
        }.sortedByDescending { it.score }

        Log.d(TAG, "Reranked ${results.size} candidates, top: ${results.firstOrNull()?.file?.name} " +
            "score=${results.firstOrNull()?.score}")

        return results
    }

    /**
     * Take top-K after rerank, guaranteeing filename/title matches appear when detected.
     */
    fun takeTop(results: List<SearchResult>, query: EnrichedQuery, topK: Int): List<SearchResult> {
        if (results.isEmpty() || topK <= 0) return emptyList()
        val sorted = results
        val coreTokens = query.coreTokens.ifEmpty {
            MultilingualBridge.coreTokens(query.cleanQueryForEmbedding)
        }
        val pins = sorted.filter { hit ->
            QueryScoring.queryMatchesFilenameStem(
                QueryScoring.filenameStem(hit.file.name),
                coreTokens
            )
        }
        if (pins.isEmpty()) return sorted.take(topK)

        val top = sorted.take(topK).toMutableList()
        val seen = top.map { it.file.path }.toMutableSet()
        for (pin in pins) {
            if (pin.file.path in seen) continue
            if (top.size >= topK) top.removeAt(top.lastIndex)
            top.add(pin)
            seen.add(pin.file.path)
        }
        return top.sortedByDescending { it.score }
    }

    private fun denseRankBonus(lexNorm: Float, denseRank: Int?, dense: Float): Float {
        if (denseRank == null || denseRank < 0) return 0f

        if (lexNorm < SearchWeights.RERANK_LEX_NORM_RESCUE_MAX) {
            return when (denseRank) {
                in 0..4   -> SearchWeights.RERANK_DENSE_RANK_BONUS_TOP5
                in 5..19  -> SearchWeights.RERANK_DENSE_RANK_BONUS_TOP20
                in 20..99 -> SearchWeights.RERANK_DENSE_RANK_BONUS_TOP100
                else      -> 0f
            }
        }

        if (dense < SearchWeights.RERANK_DENSE_RESCUE_MIN_SCORE) return 0f
        return when (denseRank) {
            in 0..9   -> SearchWeights.RERANK_DENSE_STRONG_MODERATE_LEX_BONUS
            in 10..19 -> SearchWeights.RERANK_DENSE_GOOD_MODERATE_LEX_BONUS
            else      -> 0f
        }
    }
}
