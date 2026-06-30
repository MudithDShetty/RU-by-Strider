package com.strider.quanto

/** Full-library content/entity search — bypasses FTS caps for queries like "Tanuj" or "Nikharv". */
object ContentSearch {

    fun queryTokens(query: EnrichedQuery): List<String> =
        FilenameSearch.queryTokens(query)

    fun gatherPaths(
        db: DatabaseHelper,
        query: EnrichedQuery,
        totalCount: Int? = null,
        limit: Int? = null,
        stubCache: MutableMap<String, IndexedFileStub>? = null
    ): List<String> {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val indexed = totalCount ?: db.getTotalCount()
        val sqlLimit = limit ?: RetrievalScaling.tokenSqlSearchLimit(indexed)
        val sqlPaths = db.searchPathsByContentTokens(tokens, limit = sqlLimit)
        return TokenPathFilter.filterContentPaths(db, sqlPaths, tokens, stubCache)
    }
}
