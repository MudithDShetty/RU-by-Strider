package com.strider.quanto

/**
 * Tiered post-filter for SQL LIKE results: strict → relaxed (glued) → raw fallback.
 */
object TokenPathFilter {

    fun filterFilenamePaths(
        db: DatabaseHelper,
        sqlPaths: List<String>,
        tokens: List<String>
    ): List<String> {
        if (sqlPaths.isEmpty() || tokens.isEmpty()) return sqlPaths

        val stubByPath = db.loadStubsForPaths(sqlPaths).associateBy { it.path }

        fun nameStemFor(path: String): String? =
            stubByPath[path]?.let { QueryScoring.filenameStem(it.name) }

        val strict = sqlPaths.filter { path ->
            val stem = nameStemFor(path) ?: return@filter false
            tokens.all { t ->
                QueryScoring.textContainsToken(stem, t) ||
                    QueryScoring.tokenMatchesGlued(stem, t)
            }
        }
        if (strict.size >= SearchWeights.SQL_POST_FILTER_RELAXED_MIN) return strict

        val relaxed = sqlPaths.filter { path ->
            val stem = nameStemFor(path) ?: return@filter false
            QueryScoring.allTokensMatchRelaxed(stem, tokens)
        }
        if (relaxed.isNotEmpty()) return relaxed

        return sqlPaths
    }

    fun filterContentPaths(
        db: DatabaseHelper,
        sqlPaths: List<String>,
        tokens: List<String>
    ): List<String> {
        if (sqlPaths.isEmpty() || tokens.isEmpty()) return sqlPaths

        val stubByPath = db.loadStubsForPaths(sqlPaths).associateBy { it.path }

        fun fieldsFor(path: String): Triple<String, String, String>? {
            val stub = stubByPath[path] ?: return null
            val nameStem = QueryScoring.filenameStem(stub.name)
            val meta = stub.metadata.metadataString.lowercase()
            val content = stub.metadata.contentSnippet?.lowercase() ?: ""
            val entities = stub.metadata.extractedEntities.joinToString(" ").lowercase()
            return Triple(nameStem, meta, content + " " + entities)
        }

        val strict = sqlPaths.filter { path ->
            val (nameStem, meta, contentEntities) = fieldsFor(path) ?: return@filter false
            QueryScoring.allTokensMatchStrict(nameStem, meta, contentEntities, "", tokens)
        }
        if (strict.size >= SearchWeights.SQL_POST_FILTER_RELAXED_MIN) return strict

        val relaxed = sqlPaths.filter { path ->
            val (nameStem, meta, contentEntities) = fieldsFor(path) ?: return@filter false
            tokens.all { t ->
                QueryScoring.tokenMatchesInFields(nameStem, meta, contentEntities, "", t) ||
                    QueryScoring.tokenMatchesGlued(nameStem, t)
            }
        }
        if (relaxed.isNotEmpty()) return relaxed

        return sqlPaths
    }
}
