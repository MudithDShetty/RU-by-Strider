package com.strider.quanto

import android.util.Log

private const val TAG = "GraniteReranker"

/**
 * Second-stage reranker using Granite bi-encoder + token overlap on metadata.
 *
 * Matches the production RAG pattern: retrieve broadly, then rerank top-K with a
 * stronger relevance signal. True cross-encoders need a separate model; this uses
 * Granite embeddings (indexed from file content sentences) plus metadata token overlap
 * to approximate cross-encoder interaction — especially for random filenames where
 * content lives in the embedded metadata string, not the filename.
 */
object GraniteReranker {

    fun rerank(
        query: EnrichedQuery,
        candidates: List<IndexedFile>,
        lexicalScores: Map<String, Float>,
        lexicalRankMap: Map<String, Int>,
        denseRankMap: Map<String, Int>,
        engine: EmbeddingEngine
    ): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()

        engine.prepareForSearch()
        val queryEmb = engine.embed(query.cleanQueryForEmbedding)
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
            val dense = engine.cosineSimilarity(queryEmb, file.embedding)
            val lexNorm = (lexicalScores[file.path] ?: 0f) / maxLex
            val (lexW, denseW) = when {
                query.periodHints.isNotEmpty() && lexNorm > 0.25f -> 0.72f to 0.28f
                lexNorm > 0.65f -> 0.55f to 0.45f
                lexNorm < 0.12f -> 0.10f to 0.90f
                else            -> 0.30f to 0.70f
            }

            // Token overlap on metadata, content, and filename
            val meta = file.metadata.metadataString.lowercase()
            val content = file.metadata.contentSnippet?.lowercase() ?: ""
            val nameNorm = file.name.lowercase()
                .substringBeforeLast(".")
                .replace(Regex("[_\\-.]"), " ")

            val overlapHits = queryTokens.count { t ->
                meta.contains(t) || content.contains(t) || nameNorm.contains(t)
            }
            val overlap = overlapHits.toFloat() / coreTokens.size.coerceAtLeast(1)

            val nameTokens = nameNorm.split(Regex("\\s+")).filter { it.isNotBlank() }
            val nameCoverage = if (nameTokens.isNotEmpty()) {
                nameTokens.count { nt -> coreTokens.any { q -> nt == q || nt.contains(q) } }
                    .toFloat() / nameTokens.size
            } else 0f

            var score = lexNorm * lexW + dense * denseW + overlap * 0.25f + nameCoverage * 0.20f

            val langMult = MultilingualBridge.languageHintMultiplier(
                query.languageHint, file.name, meta, content
            )
            val categoryMult = if (query.categoryHints.any { it in file.categories }) 1.12f else 1f
            val timeMult = if (query.timeHint != null && file.metadata.ageBucket == query.timeHint) 1.08f else 1f
            val specificityMult = QueryScoring.specificityMultiplier(
                nameNorm, meta, content, coreTokens, query.periodHints
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
}
