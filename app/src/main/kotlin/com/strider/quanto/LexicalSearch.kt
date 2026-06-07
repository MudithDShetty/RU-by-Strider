package com.strider.quanto

import android.util.Log

private const val TAG = "LexicalSearch"

/** In-memory lexical scorer — no SQLite FTS, no ONNX. Always query-specific. */
data class LexicalHit(
    val stub: IndexedFileStub,
    val score: Float
)

object LexicalSearch {

    private val AUDIO_EXT = setOf("mp3", "aac", "flac", "wav", "m4a", "ogg")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "3gp")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    private val DOC_EXT = setOf(
        "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "html", "htm"
    )

    fun search(
        stubs: List<IndexedFileStub>,
        query: EnrichedQuery,
        limit: Int = 50
    ): List<LexicalHit> {
        val cleanTokens = query.coreTokens.ifEmpty { tokenize(query.cleanQueryForEmbedding) }
        val matchTokens = if (query.lexicalTokens.isNotEmpty()) {
            (cleanTokens + query.lexicalTokens).distinct()
        } else {
            cleanTokens
        }
        if (matchTokens.isEmpty()) return emptyList()

        val synonymTokens = tokenize(query.keywordsForBm25)
            .filter { it !in matchTokens.toSet() }
            .take(8)

        val hits = stubs.mapNotNull { stub ->
            val score = score(stub, matchTokens, cleanTokens, synonymTokens, query)
            if (score > 0f) LexicalHit(stub, score) else null
        }.sortedByDescending { it.score }

        Log.d(TAG, "Lexical '${query.cleanQueryForEmbedding}' → ${hits.size} hits, " +
            "top: ${hits.firstOrNull()?.stub?.name ?: "none"} " +
            "score: ${hits.firstOrNull()?.score ?: 0f}")

        return hits.take(limit)
    }

    private fun score(
        stub: IndexedFileStub,
        matchTokens: List<String>,
        cleanTokens: List<String>,
        synonymTokens: List<String>,
        query: EnrichedQuery
    ): Float {
        val nameStem = QueryScoring.filenameStem(stub.name)
        val nameNorm = nameStem
        val nameRaw  = stub.name.lowercase()
        val nameTokens = nameStem.split(Regex("\\s+")).filter { it.isNotBlank() }
        val meta     = stub.metadata.metadataString.lowercase()
        val content  = stub.metadata.contentSnippet?.lowercase() ?: ""
        val folder   = stub.path.substringBeforeLast("/").substringAfterLast("/").lowercase()
        val ext      = stub.extension.lowercase()
        var score    = 0f

        for (token in matchTokens) {
            val isCore = token in cleanTokens
            val weight = if (isCore) 1f else 0.65f
            when {
                nameNorm == token || nameRaw == "$token.${stub.extension}" -> score += 100f * weight
                nameNorm.startsWith("$token ") || nameNorm.endsWith(" $token") -> score += 80f * weight
                QueryScoring.textContainsToken(nameNorm, token) -> score += 50f * weight
            }
            if (ext == token) score += 40f * weight
            if (QueryScoring.textContainsToken(folder, token)) score += 15f * weight
            if (QueryScoring.textContainsToken(meta, token)) score += 8f * weight
            if (QueryScoring.textContainsToken(content, token)) score += 35f * weight
        }

        score += filenameCoverageBoost(nameTokens, cleanTokens)

        for (period in query.periodHints) {
            val pLower = period.lowercase()
            if (nameNorm.contains(pLower) || meta.contains(pLower)) score += 90f
        }

        // Synonyms only help single-token vague queries — skip when query is specific
        val useSynonyms = cleanTokens.size < 2 && query.periodHints.isEmpty()
        if (useSynonyms) {
            for (token in synonymTokens) {
                if (QueryScoring.textContainsToken(nameNorm, token)) score += 4f
                if (QueryScoring.textContainsToken(meta, token)) score += 2f
            }
        }

        val langMult = MultilingualBridge.languageHintMultiplier(
            query.languageHint, stub.name, meta, content
        )
        val categoryMult = if (query.categoryHints.any { it in stub.categories }) 1.15f else 1f
        val timeMult = if (query.timeHint != null && stub.metadata.ageBucket == query.timeHint) 1.1f else 1f
        val typeMult = typeHintMultiplier(query, ext)
        val specificityMult = QueryScoring.specificityMultiplier(
            nameStem, meta, content, cleanTokens, query.periodHints
        )
        val ownerMult = OwnerMatcher.scoreMultiplier(
            stub.metadata.ownerEntities,
            stub.metadata.ownerConfidence,
            query.ownerIntent,
            query.ownerTargetTokens
        )

        score = QueryScoring.applyMultiplierChain(
            score, langMult, categoryMult, timeMult, typeMult, specificityMult, ownerMult
        )

        return score
    }

    private fun typeHintMultiplier(query: EnrichedQuery, ext: String): Float {
        when {
            query.typeHint?.contains("audio") == true -> {
                if (ext in AUDIO_EXT) return 1.8f
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
            }
            query.typeHint?.contains("video") == true -> {
                if (ext in VIDEO_EXT) return 1.8f
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
            }
            query.typeHint?.contains("photo") == true || query.typeHint?.contains("image") == true -> {
                if (ext in IMAGE_EXT) return 1.8f
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
            }
            query.categoryHints.contains(Category.MEDIA) && isMediaQuery(
                query.coreTokens.ifEmpty { tokenize(query.cleanQueryForEmbedding) }
            ) -> {
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
                if (ext in AUDIO_EXT + VIDEO_EXT + IMAGE_EXT) return 1.5f
            }
        }
        return 1f
    }

    /** Boost when query tokens cover the filename stem (e.g. q2 + budget → q2_budget.txt). */
    private fun filenameCoverageBoost(nameTokens: List<String>, queryTokens: List<String>): Float {
        if (nameTokens.isEmpty() || queryTokens.isEmpty()) return 0f
        val querySet = queryTokens.toSet()
        val matchedInName = nameTokens.count { nt ->
            querySet.any { q -> nt == q || nt.contains(q) || q.contains(nt) }
        }
        if (matchedInName == 0) return 0f

        var boost = (matchedInName.toFloat() / nameTokens.size) * 80f
        if (nameTokens.all { nt -> querySet.any { q -> nt == q || nt.contains(q) || q.contains(nt) } }) {
            boost += 100f
        }
        return boost
    }

    private fun isMediaQuery(tokens: List<String>): Boolean =
        tokens.any { it in setOf("music", "song", "mp3", "audio", "playlist", "video", "movie", "photo") }

    private fun tokenize(text: String): List<String> = MultilingualBridge.tokenize(text)
}
