package com.strider.quanto

private const val TAG = "DiskSearch"

/**
 * Disk-backed candidate retrieval for libraries of any size.
 * FTS + SQL narrow the pool; full lexical scoring runs only on loaded stubs.
 * Limits scale with [DatabaseHelper.getTotalCount].
 */
object DiskSearch {

    fun gatherCandidatePaths(
        db: DatabaseHelper,
        query: EnrichedQuery,
        filenamePaths: List<String>? = null,
        contentPaths: List<String>? = null,
        totalCount: Int? = null
    ): List<String> =
        gatherCandidatePathsInternal(db, query, expanded = false, filenamePaths, contentPaths, totalCount)

    fun gatherExpandedCandidatePaths(
        db: DatabaseHelper,
        query: EnrichedQuery,
        filenamePaths: List<String>? = null,
        contentPaths: List<String>? = null,
        totalCount: Int? = null
    ): List<String> =
        gatherCandidatePathsInternal(db, query, expanded = true, filenamePaths, contentPaths, totalCount)

    private fun gatherCandidatePathsInternal(
        db: DatabaseHelper,
        query: EnrichedQuery,
        expanded: Boolean,
        preFilenamePaths: List<String>? = null,
        preContentPaths: List<String>? = null,
        preTotalCount: Int? = null
    ): List<String> {
        val totalCount = preTotalCount ?: db.getTotalCount()
        val ftsLimit = RetrievalScaling.ftsLimit(totalCount, expanded)
        val catLimit = RetrievalScaling.categoryLimit(totalCount, expanded)
        val maxPaths = RetrievalScaling.maxCandidatePaths(totalCount, expanded)

        val paths = linkedSetOf<String>()

        val filenamePaths = preFilenamePaths ?: FilenameSearch.gatherPaths(db, query)
        filenamePaths.forEach { paths.add(it) }
        RuLog.d(TAG) { "Filename candidates: ${filenamePaths.size}" }

        val contentPaths = preContentPaths ?: ContentSearch.gatherPaths(db, query)
        contentPaths.forEach { paths.add(it) }
        RuLog.d(TAG) { "Content candidates: ${contentPaths.size}" }

        val ftsHits = db.ftsSearch(
            query.cleanQueryForEmbedding,
            query.keywordsForBm25,
            limit = ftsLimit
        )
        ftsHits.forEach { (path, _) -> paths.add(path) }
        RuLog.d(TAG) { "FTS candidates (${if (expanded) "expanded" else "normal"}): ${ftsHits.size} / limit $ftsLimit" }

        if (query.categoryHints.isNotEmpty()) {
            val catPaths = db.loadPathsByCategories(query.categoryHints, catLimit)
            catPaths.forEach { paths.add(it) }
            RuLog.d(TAG) { "Category candidates: ${catPaths.size}" }
        }

        val isSpecific = query.periodHints.isNotEmpty() ||
            QueryScoring.tokenize(query.cleanQueryForEmbedding).size >= 2

        val ftsWeak = ftsHits.size < if (expanded) 80 else 40
        val needsRecentFill = (!isSpecific || query.categoryHints.isEmpty()) && ftsWeak
        if (needsRecentFill) {
            val recentLimit = scaledRecentLimit(totalCount, expanded, isSpecific)
            val recent = db.loadRecentPaths(recentLimit)
            recent.forEach { paths.add(it) }
            RuLog.d(TAG) {
                "Recent candidates: ${recent.size} (specific=$isSpecific expanded=$expanded ftsWeak=$ftsWeak)"
            }
        }

        val pinnedPaths = (filenamePaths + contentPaths).distinct()
        val result = mergeExactMatchPriority(paths, pinnedPaths, maxPaths)
        RuLog.d(TAG) { "Total candidate pool: ${result.size} / $totalCount indexed (cap $maxPaths)" }
        return result
    }

    /** Filename and content SQL hits are never dropped when the general pool is capped. */
    private fun mergeExactMatchPriority(
        paths: LinkedHashSet<String>,
        pinnedPaths: List<String>,
        maxPaths: Int
    ): List<String> {
        if (pinnedPaths.isEmpty()) return paths.take(maxPaths)
        val capped = paths.take(maxPaths).toMutableList()
        val seen = capped.toMutableSet()
        for (path in pinnedPaths) {
            if (path !in seen) {
                capped.add(0, path)
                seen.add(path)
            }
        }
        return capped
    }

    private fun scaledRecentLimit(totalCount: Int, expanded: Boolean, isSpecific: Boolean): Int {
        val base = when {
            expanded && !isSpecific -> 800
            expanded -> 400
            !isSpecific -> 400
            else -> 150
        }
        val scaled = maxOf(40, totalCount / 40)
        return minOf(base, scaled)
    }
}
