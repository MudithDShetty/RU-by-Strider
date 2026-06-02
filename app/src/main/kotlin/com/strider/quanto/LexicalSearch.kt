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
                nameNorm.contains(" $token ") || nameNorm.contains(token) -> score += 50f * weight
            }
            if (ext == token) score += 40f * weight
            if (folder.contains(token)) score += 15f * weight
            if (meta.contains(token)) score += 8f * weight
            if (content.contains(token)) score += 25f * weight
        }

        score += filenameCoverageBoost(nameTokens, cleanTokens)

        score *= MultilingualBridge.languageHintMultiplier(
            query.languageHint, stub.name, meta, content
        )

        for (period in query.periodHints) {
            val pLower = period.lowercase()
            if (nameNorm.contains(pLower) || meta.contains(pLower)) score += 90f
        }

        // Synonyms only help single-token vague queries — skip when query is specific
        val useSynonyms = cleanTokens.size < 2 && query.periodHints.isEmpty()
        if (useSynonyms) {
            for (token in synonymTokens) {
                if (nameNorm.contains(token)) score += 4f
                if (meta.contains(token)) score += 2f
            }
        }

        if (query.categoryHints.any { it in stub.categories }) score *= 1.15f

        when {
            query.typeHint?.contains("audio") == true -> {
                if (ext in AUDIO_EXT) score *= 1.8f else if (ext in DOC_EXT) score *= 0.05f
            }
            query.typeHint?.contains("video") == true -> {
                if (ext in VIDEO_EXT) score *= 1.8f else if (ext in DOC_EXT) score *= 0.05f
            }
            query.typeHint?.contains("photo") == true || query.typeHint?.contains("image") == true -> {
                if (ext in IMAGE_EXT) score *= 1.8f else if (ext in DOC_EXT) score *= 0.05f
            }
            query.categoryHints.contains(Category.MEDIA) && isMediaQuery(cleanTokens) -> {
                if (ext in DOC_EXT) score *= 0.05f
                if (ext in AUDIO_EXT + VIDEO_EXT + IMAGE_EXT) score *= 1.5f
            }
        }

        if (query.timeHint != null && stub.metadata.ageBucket == query.timeHint) score *= 1.1f

        score *= QueryScoring.specificityMultiplier(
            nameStem, meta, content, cleanTokens, query.periodHints
        )

        score *= OwnerMatcher.scoreMultiplier(
            stub.metadata.ownerEntities,
            stub.metadata.ownerConfidence,
            query.ownerIntent,
            query.ownerTargetTokens
        )

        return score
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
