package com.strider.quanto

import android.util.Log

private const val TAG = "SearchDebug"

/** Golden query case from Tasks.md test matrix — dev-mode eval only. */
data class GoldenQueryCase(
    val query: String,
    val expectedFileSubstring: String,
    val maxShownRank: Int
)

object SearchEval {

    val GOLDEN_CASES = listOf(
        GoldenQueryCase("nda quantoo", "quantoo", 1),
        GoldenQueryCase("Tanuj", "quantoo", 3),
        GoldenQueryCase("Nikharv", "quantoo", 3)
    )

    data class GoldenResult(
        val case: GoldenQueryCase,
        val inCandidatePool: Boolean,
        val inRerankPool: Boolean,
        val shownRank: Int,
        val matchedPath: String?
    ) {
        val poolPass: Boolean get() = inCandidatePool
        val rerankPass: Boolean get() = inRerankPool
        val rankPass: Boolean get() = shownRank in 1..case.maxShownRank
        val allPass: Boolean get() = poolPass && rerankPass && rankPass
    }

    fun evaluate(
        query: String,
        candidatePaths: List<String>,
        fusedPaths: List<String>,
        results: List<SearchResult>
    ): GoldenResult? {
        val case = GOLDEN_CASES.firstOrNull {
            it.query.equals(query.trim(), ignoreCase = true)
        } ?: return null

        val needle = case.expectedFileSubstring.lowercase()
        fun pathMatches(path: String): Boolean {
            val name = path.substringAfterLast('/').lowercase()
            return needle in name || needle in path.lowercase()
        }

        val poolPath = candidatePaths.firstOrNull { pathMatches(it) }
        val fusedPath = fusedPaths.firstOrNull { pathMatches(it) }
        val resultIdx = results.indexOfFirst { pathMatches(it.file.path) }
        val shownRank = if (resultIdx >= 0) resultIdx + 1 else -1

        return GoldenResult(
            case = case,
            inCandidatePool = poolPath != null,
            inRerankPool = fusedPath != null,
            shownRank = shownRank,
            matchedPath = poolPath ?: fusedPath ?: results.getOrNull(resultIdx)?.file?.path
        )
    }

    fun logEvaluation(result: GoldenResult) {
        val c = result.case
        val status = if (result.allPass) "PASS" else "FAIL"
        Log.i(
            TAG,
            "Golden eval [$status] query=\"${c.query}\" " +
                "pool=${result.inCandidatePool} rerank=${result.inRerankPool} " +
                "shown=${if (result.shownRank > 0) "#${result.shownRank}" else "—"} " +
                "(max #${c.maxShownRank}) file=${result.matchedPath?.substringAfterLast('/') ?: "missing"}"
        )
        if (!result.poolPass) Log.w(TAG, "  → missed candidate pool")
        if (!result.rerankPass) Log.w(TAG, "  → missed rerank/fusion pool")
        if (!result.rankPass) Log.w(TAG, "  → shown rank above threshold")
    }
}
