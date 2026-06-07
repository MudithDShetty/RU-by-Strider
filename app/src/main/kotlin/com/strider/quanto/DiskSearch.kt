package com.strider.quanto

import android.util.Log

private const val TAG = "DiskSearch"

/**
 * Disk-backed candidate retrieval for libraries of any size.
 * FTS + SQL narrow the pool; full lexical scoring runs only on loaded stubs.
 */
object DiskSearch {

    private const val FTS_CANDIDATE_LIMIT = 500
    private const val CATEGORY_CANDIDATE_LIMIT = 500
    private const val RECENT_VAGUE_LIMIT = 1000
    private const val RECENT_SPECIFIC_LIMIT = 400
    private const val MAX_CANDIDATE_PATHS = 1800

    private const val EXPANDED_FTS_LIMIT = 800
    private const val EXPANDED_CATEGORY_LIMIT = 800
    private const val EXPANDED_RECENT_VAGUE = 1500
    private const val EXPANDED_RECENT_SPECIFIC = 800
    private const val EXPANDED_MAX_PATHS = 2500

    /** Gather path candidates from FTS, category, and recency — never loads the full library. */
    fun gatherCandidatePaths(db: DatabaseHelper, query: EnrichedQuery): List<String> =
        gatherCandidatePathsInternal(db, query, expanded = false)

    /** Wider pool used when primary search is empty or low-confidence. */
    fun gatherExpandedCandidatePaths(db: DatabaseHelper, query: EnrichedQuery): List<String> =
        gatherCandidatePathsInternal(db, query, expanded = true)

    private fun gatherCandidatePathsInternal(
        db: DatabaseHelper,
        query: EnrichedQuery,
        expanded: Boolean
    ): List<String> {
        val ftsLimit = if (expanded) EXPANDED_FTS_LIMIT else FTS_CANDIDATE_LIMIT
        val catLimit = if (expanded) EXPANDED_CATEGORY_LIMIT else CATEGORY_CANDIDATE_LIMIT
        val maxPaths = if (expanded) EXPANDED_MAX_PATHS else MAX_CANDIDATE_PATHS

        val paths = linkedSetOf<String>()

        val ftsHits = db.ftsSearch(
            query.cleanQueryForEmbedding,
            query.keywordsForBm25,
            limit = ftsLimit
        )
        ftsHits.forEach { (path, _) -> paths.add(path) }
        Log.d(TAG, "FTS candidates (${if (expanded) "expanded" else "normal"}): ${ftsHits.size}")

        if (query.categoryHints.isNotEmpty()) {
            val catPaths = db.loadPathsByCategories(query.categoryHints, catLimit)
            catPaths.forEach { paths.add(it) }
            Log.d(TAG, "Category candidates: ${catPaths.size}")
        }

        val isSpecific = query.periodHints.isNotEmpty() ||
            QueryScoring.tokenize(query.cleanQueryForEmbedding).size >= 2

        val recentLimit = when {
            expanded && !isSpecific -> EXPANDED_RECENT_VAGUE
            expanded -> EXPANDED_RECENT_SPECIFIC
            !isSpecific -> RECENT_VAGUE_LIMIT
            else -> RECENT_SPECIFIC_LIMIT
        }
        if (!isSpecific || query.categoryHints.isEmpty()) {
            val recent = db.loadRecentPaths(recentLimit)
            recent.forEach { paths.add(it) }
            Log.d(TAG, "Recent candidates: ${recent.size} (specific=$isSpecific expanded=$expanded)")
        }

        val result = paths.take(maxPaths)
        Log.d(TAG, "Total candidate pool: ${result.size}")
        return result
    }

    /** Paths for dense (semantic) retrieval — disk queries, no in-memory stub list. */
    fun gatherDenseCandidatePaths(
        db: DatabaseHelper,
        query: EnrichedQuery,
        lexicalPaths: List<String>,
        expanded: Boolean = false
    ): List<String> {
        val pathSet = linkedSetOf<String>()
        lexicalPaths.take(if (expanded) 50 else 30).forEach { pathSet.add(it) }

        val isSpecific = query.periodHints.isNotEmpty() ||
            QueryScoring.tokenize(query.cleanQueryForEmbedding).size >= 2

        val catLimit = if (expanded) EXPANDED_CATEGORY_LIMIT else CATEGORY_CANDIDATE_LIMIT
        if (query.categoryHints.isNotEmpty()) {
            db.loadPathsByCategories(query.categoryHints, catLimit)
                .forEach { pathSet.add(it) }
        }

        val recentLimit = when {
            expanded && !isSpecific -> EXPANDED_RECENT_VAGUE
            expanded && query.categoryHints.isEmpty() -> EXPANDED_RECENT_SPECIFIC
            !expanded && !isSpecific -> RECENT_VAGUE_LIMIT
            !expanded && query.categoryHints.isEmpty() -> RECENT_SPECIFIC_LIMIT
            else -> 0
        }
        if (recentLimit > 0) {
            db.loadRecentPaths(recentLimit).forEach { pathSet.add(it) }
        }

        val maxPaths = if (expanded) EXPANDED_MAX_PATHS else MAX_CANDIDATE_PATHS
        return pathSet.take(maxPaths).toList()
    }
}
