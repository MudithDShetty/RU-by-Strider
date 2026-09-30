package com.strider.ru

import android.util.Log

private const val TAG = "SearchDebug"

/** Rank of a tracked file through each retrieval stage (1-based ranks; -1 = not present). */
data class TargetFileReport(
    val hint: String,
    val matchedPath: String?,
    val matchedName: String?,
    val denseRank: Int,
    val denseScore: Float,
    val denseTopK: Int,
    val lexicalRank: Int,
    val lexicalScore: Float,
    val shownRank: Int,
    val inCandidatePool: Boolean,
    val inFusedPool: Boolean,
    val semanticEnabled: Boolean
) {
    fun summaryLine(): String {
        if (matchedPath == null) {
            return "Track \"$hint\": no indexed file name/path match"
        }
        val name = matchedName ?: matchedPath.substringAfterLast('/')
        if (!semanticEnabled) {
            val lex = if (lexicalRank > 0) "#$lexicalRank" else "—"
            val shown = if (shownRank > 0) "#$shownRank" else "not shown"
            return "Track \"$hint\" → $name · lexical $lex · shown $shown"
        }
        val dense = when {
            denseRank <= 0 -> "not scanned"
            denseRank > denseTopK -> "#$denseRank (cutoff top $denseTopK)"
            else -> "#$denseRank (${"%.2f".format(denseScore)})"
        }
        val lex = if (lexicalRank > 0) "#$lexicalRank" else "—"
        val shown = if (shownRank > 0) "#$shownRank" else "not shown"
        val pool = if (inCandidatePool) "pool ✓" else "pool ✗"
        return "Track \"$hint\" → $name · dense $dense · lexical $lex · $shown · $pool"
    }

    fun detailLines(): List<String> = buildList {
        add("=== Search debug: \"$hint\" ===")
        if (matchedPath == null) {
            add("No file matched (check spelling / partial name)")
            return@buildList
        }
        add("File: $matchedName")
        add("Path: $matchedPath")
        add("In FTS/candidate pool: ${if (inCandidatePool) "yes" else "NO — lexical never saw it"}")
        add("In RRF/fusion pool: ${if (inFusedPool) "yes" else "no"}")
        if (semanticEnabled) {
            add("Dense rank: ${denseRank.coerceAtLeast(0)} / top-$denseTopK cutoff (${"%.3f".format(denseScore)})")
            if (denseRank > denseTopK) add("→ Dropped before rerank (raised top-K fixes this)")
            if (denseRank <= 0) add("→ Dense scan did not run")
        }
        add("Lexical rank: ${if (lexicalRank > 0) "#$lexicalRank (${lexicalScore.toInt()} pts)" else "not scored"}")
        add("Shown in UI: ${if (shownRank > 0) "#$shownRank" else "NOT in results list"}")
        when {
            shownRank == 1 -> add("Verdict: OK")
            shownRank in 2..10 -> add("Verdict: found but ranked low — tune reranker")
            denseRank > denseTopK && denseRank > 0 -> add("Verdict: semantic match exists but outside top-$denseTopK")
            !inCandidatePool && lexicalRank <= 0 -> add("Verdict: missed candidate pool — check filename/content/entity SQL + FTS")
            denseRank <= 0 && semanticEnabled -> add("Verdict: no semantic signal — embedding/metadata gap")
            inFusedPool && shownRank <= 0 -> add("Verdict: lost in reranking — check filename-query / hybrid score")
            else -> add("Verdict: lost in reranking")
        }
    }

    fun logToLogcat() {
        detailLines().forEach { Log.i(TAG, it) }
    }
}

data class SearchDiagnostics(
    val totalIndexed: Int,
    val candidatePoolSize: Int,
    val denseTopK: Int,
    val semanticEnabled: Boolean,
    val targetReport: TargetFileReport?
) {
    fun statusSuffix(): String = targetReport?.summaryLine()?.let { "\n$it" } ?: ""
}

data class DenseScanResult(
    val top: List<Pair<String, Float>>,
    val tracked: List<DenseScanResult.TrackedRank>,
    val totalScanned: Int
) {
    data class TrackedRank(
        val path: String,
        val rank: Int,
        val score: Float,
        val inTopK: Boolean
    )

    fun bestTrackedForHint(hints: List<String>): TrackedRank? {
        if (tracked.isEmpty() || hints.isEmpty()) return null
        val lowered = hints.map { it.lowercase() }.filter { it.isNotBlank() }
        return tracked
            .filter { tr ->
                val name = tr.path.substringAfterLast('/').lowercase()
                lowered.any { h -> h in tr.path.lowercase() || h in name }
            }
            .minByOrNull { it.rank }
    }
}

object SearchDiagnosticsBuilder {

    fun build(
        hint: String?,
        query: String? = null,
        totalIndexed: Int,
        candidatePaths: List<String>,
        lexicalHits: List<LexicalHit>,
        denseScan: DenseScanResult?,
        fusedPaths: List<String>,
        results: List<SearchResult>,
        semanticEnabled: Boolean,
        denseTopK: Int
    ): SearchDiagnostics? {
        if (hint.isNullOrBlank()) return null

        val lowered = hint.lowercase().trim()
        val matchedPath = findMatchingPath(lowered, candidatePaths, lexicalHits, denseScan, results)
        val matchedName = matchedPath?.substringAfterLast('/')

        val lexicalIdx = lexicalHits.indexOfFirst { matchesHint(lowered, it.stub.path) }
        val lexicalRank = if (lexicalIdx >= 0) lexicalIdx + 1 else -1
        val lexicalScore = lexicalHits.getOrNull(lexicalIdx)?.score ?: 0f

        val tracked = denseScan?.bestTrackedForHint(listOf(lowered))
        val denseRank = tracked?.rank ?: -1
        val denseScore = tracked?.score ?: 0f

        val shownIdx = results.indexOfFirst { matchesHint(lowered, it.file.path) }
        val shownRank = if (shownIdx >= 0) shownIdx + 1 else -1

        val inPool = matchedPath != null && candidatePaths.any { matchesHint(lowered, it) }
        val inFused = matchedPath != null && fusedPaths.any { matchesHint(lowered, it) }

        return SearchDiagnostics(
            totalIndexed = totalIndexed,
            candidatePoolSize = candidatePaths.size,
            denseTopK = denseTopK,
            semanticEnabled = semanticEnabled,
            targetReport = TargetFileReport(
                hint = hint.trim(),
                matchedPath = matchedPath ?: tracked?.path,
                matchedName = matchedName ?: tracked?.path?.substringAfterLast('/'),
                denseRank = denseRank,
                denseScore = denseScore,
                denseTopK = denseTopK,
                lexicalRank = lexicalRank,
                lexicalScore = lexicalScore,
                shownRank = shownRank,
                inCandidatePool = inPool,
                inFusedPool = inFused,
                semanticEnabled = semanticEnabled
            )
        ).also {
            if (BuildConfig.DEBUG) {
                it.targetReport?.logToLogcat()
            }
            if (BuildConfig.DEBUG && !query.isNullOrBlank()) {
                SearchEval.evaluate(query, candidatePaths, fusedPaths, results)?.let { golden ->
                    SearchEval.logEvaluation(golden)
                }
            }
        }
    }

    private fun findMatchingPath(
        hint: String,
        candidatePaths: List<String>,
        lexicalHits: List<LexicalHit>,
        denseScan: DenseScanResult?,
        results: List<SearchResult>
    ): String? {
        val allPaths = buildList {
            addAll(results.map { it.file.path })
            addAll(lexicalHits.map { it.stub.path })
            addAll(candidatePaths)
            denseScan?.tracked?.forEach { add(it.path) }
        }.distinct()
        return allPaths.firstOrNull { matchesHint(hint, it) }
            ?: denseScan?.tracked?.firstOrNull { matchesHint(hint, it.path) }?.path
    }

    fun matchesHint(hint: String, path: String): Boolean {
        val h = hint.lowercase()
        val name = path.substringAfterLast('/').lowercase()
        return h in path.lowercase() || h in name || name.contains(h)
    }
}
