package com.strider.ru

/**
 * Retrieval depth scales with library size.
 * At ~24k files a fixed top-80 dense cutoff drops 99.7% of the index before reranking.
 */
object RetrievalScaling {

    /** How many dense hits survive the full-library scan (before RRF / rerank). */
    fun denseTopK(totalCount: Int): Int = when {
        totalCount <= 2_000  -> 50
        totalCount <= 5_000  -> 80
        totalCount <= 10_000 -> 120
        totalCount <= 20_000 -> 200
        else -> minOf(350, 80 + totalCount / 70)
    }

    /** RRF fusion width before reranking. */
    fun rrfFusionTopK(totalCount: Int): Int =
        minOf(100, maxOf(30, totalCount / 250))

    /** Guaranteed dense slots passed through to reranking. */
    fun densePinCount(totalCount: Int): Int =
        minOf(60, maxOf(15, denseTopK(totalCount) / 5))

    /** Max candidates the reranker evaluates. */
    fun rerankPoolCap(totalCount: Int): Int =
        minOf(180, maxOf(45, denseTopK(totalCount) * 2 / 3))

    /** In-memory lexical scoring limit on the FTS candidate pool. */
    fun lexicalSearchLimit(totalCount: Int): Int =
        minOf(120, maxOf(50, totalCount / 200))

    fun ftsLimit(totalCount: Int, expanded: Boolean): Int {
        val base = if (expanded) 1_200 else 600
        return minOf(2_500, base + totalCount / 15)
    }

    fun categoryLimit(totalCount: Int, expanded: Boolean): Int {
        val base = if (expanded) 800 else 500
        return minOf(1_500, base + totalCount / 30)
    }

    fun maxCandidatePaths(totalCount: Int, expanded: Boolean): Int =
        if (expanded) minOf(5_000, maxOf(2_500, totalCount / 6))
        else minOf(4_000, maxOf(1_800, totalCount / 8))

    /** Full-library filename/content SQL pass — scales at 24k+, floor 80. */
    fun tokenSqlSearchLimit(totalCount: Int): Int =
        minOf(120, maxOf(80, totalCount / 200))

    /** Max filename + content paths prepended to rerank pool (was fixed 40). */
    fun exactMatchPinLimit(totalCount: Int): Int =
        minOf(60, maxOf(40, totalCount / 400))

    /** Lexical hits guaranteed into rerank pool after RRF (was fixed 25). */
    fun lexicalRerankPinLimit(totalCount: Int): Int =
        minOf(35, maxOf(25, totalCount / 800))

    /** Lexical top hits merged into dense scan when missing from top-K. */
    fun denseLexicalPinCount(totalCount: Int): Int =
        minOf(30, maxOf(20, totalCount / 1_000))
}
