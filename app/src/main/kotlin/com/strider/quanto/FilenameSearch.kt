package com.strider.quanto

/** Full-library filename matching — bypasses FTS caps for queries like "nda quantoo". */
object FilenameSearch {

    fun queryTokens(query: EnrichedQuery): List<String> {
        val fromCore = query.coreTokens.filter { it.length >= 2 }
        if (fromCore.isNotEmpty()) return fromCore.distinct()
        val fromLexical = query.lexicalTokens.filter { it.length >= 2 }
        if (fromLexical.isNotEmpty()) return fromLexical.distinct()
        return MultilingualBridge.coreTokens(query.cleanQueryForEmbedding)
            .filter { it.length >= 2 }
            .distinct()
    }

    fun gatherPaths(db: DatabaseHelper, query: EnrichedQuery, limit: Int? = null): List<String> {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val sqlLimit = limit ?: RetrievalScaling.tokenSqlSearchLimit(db.getTotalCount())
        val sqlPaths = db.searchPathsByNameTokens(tokens, limit = sqlLimit)
        return TokenPathFilter.filterFilenamePaths(db, sqlPaths, tokens)
    }
}
