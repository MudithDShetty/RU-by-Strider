package com.strider.ru

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
            .take(SearchWeights.MAX_SYNONYM_TOKENS)

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
        val entities = stub.metadata.extractedEntities.joinToString(" ").lowercase()
        val folder   = stub.path.substringBeforeLast("/").substringAfterLast("/").lowercase()
        val ext      = stub.extension.lowercase()
        var score    = 0f

        for (token in matchTokens) {
            val isCore = token in cleanTokens
            val weight = if (isCore) 1f else SearchWeights.LEX_SYNONYM_TOKEN_WEIGHT
            when {
                nameNorm == token || nameRaw == "$token.${stub.extension}" ->
                    score += SearchWeights.LEX_NAME_EXACT * weight
                nameNorm.startsWith("$token ") || nameNorm.endsWith(" $token") ->
                    score += SearchWeights.LEX_NAME_EDGE * weight
                QueryScoring.textContainsToken(nameNorm, token) ->
                    score += SearchWeights.LEX_NAME_CONTAINS * weight
            }
            if (ext == token) score += SearchWeights.LEX_EXT_MATCH * weight
            if (QueryScoring.textContainsToken(folder, token)) score += SearchWeights.LEX_FOLDER * weight
            if (QueryScoring.textContainsToken(meta, token)) score += SearchWeights.LEX_META * weight
            if (QueryScoring.textContainsToken(content, token)) score += SearchWeights.LEX_CONTENT * weight
            if (QueryScoring.textContainsToken(entities, token)) score += SearchWeights.LEX_ENTITY * weight
        }

        if (query.queryType == QueryType.PERSON_NAME && entities.isNotBlank()) {
            val entityHits = cleanTokens.count { QueryScoring.textContainsToken(entities, it) }
            if (entityHits == cleanTokens.size) score += SearchWeights.LEX_PERSON_NAME_FULL
            else if (entityHits > 0) score += entityHits * SearchWeights.LEX_PERSON_NAME_PER_HIT
        }

        score += QueryScoring.filenameCoverageBoost(nameTokens, cleanTokens)

        for (period in query.periodHints) {
            val pLower = period.lowercase()
            if (QueryScoring.textContainsToken(nameNorm, pLower) ||
                QueryScoring.textContainsToken(meta, pLower)
            ) {
                score += SearchWeights.LEX_PERIOD_HIT
            }
        }

        val useSynonyms = cleanTokens.size < 2 && query.periodHints.isEmpty()
        if (useSynonyms) {
            for (token in synonymTokens) {
                if (QueryScoring.textContainsToken(nameNorm, token)) score += SearchWeights.LEX_SYNONYM_NAME
                if (QueryScoring.textContainsToken(meta, token)) score += SearchWeights.LEX_SYNONYM_META
            }
        }

        val langMult = MultilingualBridge.languageHintMultiplier(
            query.languageHint, stub.name, meta, content
        )
        val typeMult = typeHintMultiplier(query, ext)
        val ownerMult = OwnerMatcher.scoreMultiplier(
            stub.metadata.ownerEntities,
            stub.metadata.ownerConfidence,
            query.ownerIntent,
            query.ownerTargetTokens
        )

        // Category/time boosts apply in reranker only — avoids double-counting with GraniteReranker.
        score = QueryScoring.applyMultiplierChain(score, langMult, typeMult, ownerMult)

        return score
    }

    private fun typeHintMultiplier(query: EnrichedQuery, ext: String): Float {
        when {
            query.typeHint?.contains("audio") == true -> {
                if (ext in AUDIO_EXT) return SearchWeights.LEX_TYPE_MATCH_MULT
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
            }
            query.typeHint?.contains("video") == true -> {
                if (ext in VIDEO_EXT) return SearchWeights.LEX_TYPE_MATCH_MULT
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
            }
            query.typeHint?.contains("photo") == true || query.typeHint?.contains("image") == true -> {
                if (ext in IMAGE_EXT) return SearchWeights.LEX_TYPE_MATCH_MULT
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
            }
            query.categoryHints.contains(Category.MEDIA) && isMediaQuery(
                query.coreTokens.ifEmpty { tokenize(query.cleanQueryForEmbedding) }
            ) -> {
                if (ext in DOC_EXT) return QueryScoring.SOFT_MISMATCH_PENALTY
                if (ext in AUDIO_EXT + VIDEO_EXT + IMAGE_EXT) return SearchWeights.LEX_MEDIA_MATCH_MULT
            }
        }
        return 1f
    }

    private fun isMediaQuery(tokens: List<String>): Boolean =
        tokens.any { it in setOf("music", "song", "mp3", "audio", "playlist", "video", "movie", "photo") }

    private fun tokenize(text: String): List<String> = MultilingualBridge.tokenize(text)
}
