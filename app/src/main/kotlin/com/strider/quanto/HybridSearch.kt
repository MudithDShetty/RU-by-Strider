package com.strider.quanto

import android.util.Log

private const val TAG = "HybridSearch"
private const val RRF_K = 60
private const val BM25_RRF_WEIGHT = 4f  // keyword branch dominates over dense

private val AUDIO_EXTENSIONS = setOf("mp3", "aac", "flac", "wav", "m4a", "ogg")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "3gp")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
private val DOCUMENT_EXTENSIONS = setOf(
    "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "csv", "json", "xml", "html", "htm"
)

data class HybridResult(
    val file: IndexedFile,
    val rrfScore: Float,
    val denseRank: Int,
    val bm25Rank: Int,
    val categoryMatched: Boolean
)

object HybridSearch {

    fun fuse(
        bm25Paths: List<Pair<String, Float>>,
        denseResults: List<SearchResult>,
        allFiles: Map<String, IndexedFile>,
        categoryHints: List<Category>,
        timeHint: String?,
        cleanQuery: String,
        typeHint: String?,
        topK: Int = 10
    ): List<HybridResult> {
        val rrfScores = mutableMapOf<String, Float>()
        val denseRankMap = mutableMapOf<String, Int>()
        val bm25RankMap  = mutableMapOf<String, Int>()
        val cleanTokens  = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length > 1 }

        bm25Paths.forEachIndexed { rank, (path, _) ->
            rrfScores[path] = (rrfScores[path] ?: 0f) + BM25_RRF_WEIGHT / (RRF_K + rank + 1)
            bm25RankMap[path] = rank
        }

        denseResults.forEachIndexed { rank, result ->
            val path = result.file.path
            rrfScores[path] = (rrfScores[path] ?: 0f) + 1f / (RRF_K + rank + 1)
            denseRankMap[path] = rank
        }

        return rrfScores.entries
            .sortedByDescending { it.value }
            .take(topK * 3)
            .mapNotNull { (path, baseScore) ->
                val file = allFiles[path] ?: return@mapNotNull null
                var score = baseScore

                val catMatched = categoryHints.any { it in file.categories }
                if (catMatched) score *= 1.15f

                if (timeHint != null && file.metadata.ageBucket == timeHint) score *= 1.10f

                // Filename token match is the strongest signal for short queries
                val nameLower = file.name.lowercase()
                if (cleanTokens.any { nameLower.contains(it) }) score *= 2.5f

                // Penalize wrong file types (e.g. txt when searching for music)
                when {
                    matchesTypeHint(file.extension, typeHint) -> score *= 1.3f
                    typeHint != null || isMediaCategoryQuery(categoryHints) ->
                        if (isWrongTypeForMediaQuery(file.extension, categoryHints, typeHint)) score *= 0.15f
                }

                // Dense-only weak match — likely semantic drift
                if (bm25RankMap[path] == null &&
                    (denseResults.find { it.file.path == path }?.score ?: 0f) < 0.30f
                ) {
                    score *= 0.5f
                }

                HybridResult(
                    file            = file,
                    rrfScore        = score,
                    denseRank       = denseRankMap[path] ?: -1,
                    bm25Rank        = bm25RankMap[path]  ?: -1,
                    categoryMatched = catMatched
                )
            }
            .sortedByDescending { it.rrfScore }
            .take(topK)
            .also { results ->
                Log.d(TAG, "Hybrid fusion: ${results.size} results")
                results.take(3).forEach { r ->
                    Log.d(TAG, "  ${r.file.name} — rrf:${"%.4f".format(r.rrfScore)} " +
                        "dense:${r.denseRank} bm25:${r.bm25Rank}")
                }
            }
    }

    private fun matchesTypeHint(ext: String, typeHint: String?): Boolean {
        if (typeHint == null) return false
        val e = ext.lowercase()
        return when {
            typeHint.contains("audio")                          -> e in AUDIO_EXTENSIONS
            typeHint.contains("video")                          -> e in VIDEO_EXTENSIONS
            typeHint.contains("photo") || typeHint.contains("image") -> e in IMAGE_EXTENSIONS
            typeHint.contains("spreadsheet")                      -> e in setOf("xls", "xlsx", "csv")
            typeHint.contains("presentation")                     -> e in setOf("ppt", "pptx")
            typeHint.contains("document")                         -> e in DOCUMENT_EXTENSIONS
            else -> false
        }
    }

    private fun isMediaCategoryQuery(categoryHints: List<Category>): Boolean =
        Category.MEDIA in categoryHints

    private fun isWrongTypeForMediaQuery(
        ext: String,
        categoryHints: List<Category>,
        typeHint: String?
    ): Boolean {
        val e = ext.lowercase()
        if (typeHint?.contains("audio") == true) return e !in AUDIO_EXTENSIONS
        if (typeHint?.contains("video") == true) return e !in VIDEO_EXTENSIONS
        if (typeHint?.contains("photo") == true || typeHint?.contains("image") == true) {
            return e !in IMAGE_EXTENSIONS
        }
        if (Category.MEDIA in categoryHints) {
            return e in DOCUMENT_EXTENSIONS
        }
        return false
    }
}
