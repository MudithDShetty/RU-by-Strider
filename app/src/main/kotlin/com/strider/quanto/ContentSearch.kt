package com.strider.quanto

/** Full-library content/entity search — bypasses FTS caps for queries like "Tanuj" or "Nikharv". */
object ContentSearch {

    fun queryTokens(query: EnrichedQuery): List<String> =
        FilenameSearch.queryTokens(query)

    fun gatherPaths(db: DatabaseHelper, query: EnrichedQuery, limit: Int? = null): List<String> {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val sqlLimit = limit ?: RetrievalScaling.tokenSqlSearchLimit(db.getTotalCount())
        val sqlPaths = db.searchPathsByContentTokens(tokens, limit = sqlLimit)
        return TokenPathFilter.filterContentPaths(db, sqlPaths, tokens)
    }
}
