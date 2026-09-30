package com.strider.ru

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
        val groups = FilenameSearch.retrievalTokenGroups(query)
        if (groups.isEmpty()) return emptyList()
        val indexed = totalCount ?: db.getTotalCount()
        val sqlLimit = limit ?: RetrievalScaling.tokenSqlSearchLimit(indexed)
        val paths = linkedSetOf<String>()
        for (tokens in groups) {
            val sqlPaths = db.searchPathsByContentTokens(tokens, limit = sqlLimit)
            TokenPathFilter.filterContentPaths(db, sqlPaths, tokens, stubCache)
                .forEach { paths.add(it) }
        }
        return paths.toList()
    }
}
