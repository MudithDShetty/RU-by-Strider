package com.strider.ru

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

    /** Primary + alternate AND groups for possessive self-queries (first vs last/middle name). */
    fun retrievalTokenGroups(query: EnrichedQuery): List<List<String>> {
        if (query.ownerIntent == OwnerIntent.SELF && query.ownerRetrievalPrimary.isNotEmpty()) {
            val groups = linkedSetOf<List<String>>()
            groups.add(query.ownerRetrievalPrimary)
            query.ownerRetrievalAlternates.forEach { alt ->
                if (alt.isNotEmpty()) groups.add(alt)
            }
            return groups.toList()
        }
        val base = queryTokens(query)
        if (base.isEmpty()) return emptyList()
        return listOf(base)
    }

    fun gatherPaths(
        db: DatabaseHelper,
        query: EnrichedQuery,
        totalCount: Int? = null,
        limit: Int? = null,
        stubCache: MutableMap<String, IndexedFileStub>? = null
    ): List<String> {
        val groups = retrievalTokenGroups(query)
        if (groups.isEmpty()) return emptyList()
        val indexed = totalCount ?: db.getTotalCount()
        val sqlLimit = limit ?: RetrievalScaling.tokenSqlSearchLimit(indexed)
        val paths = linkedSetOf<String>()
        for (tokens in groups) {
            val sqlPaths = db.searchPathsByNameTokens(tokens, limit = sqlLimit)
            TokenPathFilter.filterFilenamePaths(db, sqlPaths, tokens, stubCache)
                .forEach { paths.add(it) }
        }
        return paths.toList()
    }
}
