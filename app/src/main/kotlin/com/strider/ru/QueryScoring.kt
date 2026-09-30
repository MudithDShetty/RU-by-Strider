package com.strider.ru

/** Shared token-matching logic for lexical + rerank stages. */
object QueryScoring {

    /** Minimum fraction of base score retained after multiplier chains — prevents silent burial. */
    const val MULTIPLIER_FLOOR = SearchWeights.MULTIPLIER_FLOOR

    /** Soft penalty when type/language/period detectors misfire. */
    const val SOFT_MISMATCH_PENALTY = SearchWeights.SOFT_MISMATCH_PENALTY

    private val PERIOD_IN_NAME = Regex("q[1-4]")
    private val STEM_TOKEN_SPLIT = Regex("\\s+")

    fun filenameStem(name: String): String =
        name.substringBeforeLast(".")
            .lowercase()
            .replace(Regex("[_\\-.]"), " ")
            .trim()

    fun stemTokens(nameStem: String): List<String> =
        nameStem.split(STEM_TOKEN_SPLIT).filter { it.isNotBlank() }

    fun tokenize(text: String): List<String> = MultilingualBridge.tokenize(text)

    /**
     * Whole-word match for tokens ≥3 chars.
     * Shorter tokens match only at stem-token boundaries (equality or prefix/suffix).
     */
    fun textContainsToken(text: String, token: String): Boolean {
        if (token.isBlank()) return false
        val lower = text.lowercase()
        val t = token.lowercase()
        val words = stemTokens(lower)
        if (t.length >= 3) {
            if (words.any { it == t }) return true
            // Reject mid-token substring hits (e.g. "art" inside "smart", "doc" inside "document")
            if (words.any { it.length > t.length && it.contains(t) }) return false
            return Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(lower)
        }
        return words.any { word -> tokenMatchesGluedWord(word, t) }
    }

    /** Apply chained multipliers but never reduce below [MULTIPLIER_FLOOR] of base score. */
    fun applyMultiplierChain(baseScore: Float, vararg multipliers: Float): Float {
        if (baseScore <= 0f) return 0f
        val combined = multipliers.fold(1f) { acc, m -> acc * m }
        return baseScore * combined.coerceAtLeast(MULTIPLIER_FLOOR)
    }

    /** Match query token against a single filename stem token (equality or prefix/suffix). */
    fun tokenMatchesGluedWord(stemToken: String, queryToken: String): Boolean {
        if (queryToken.isBlank() || stemToken.isBlank()) return false
        val nt = stemToken.lowercase()
        val q = queryToken.lowercase()
        if (nt == q) return true
        if (q.length <= 2) {
            return nt.startsWith(q) || nt.endsWith(q)
        }
        if (nt.endsWith(q)) {
            val boundaryIdx = nt.length - q.length - 1
            if (boundaryIdx < 0) return true
            return !nt[boundaryIdx].isLetter()
        }
        if (nt.startsWith(q)) {
            val boundaryIdx = q.length
            if (boundaryIdx >= nt.length) return true
            return !nt[boundaryIdx].isLetter()
        }
        return false
    }

    /** Relaxed filename match — handles glued tokens like q2budget without mid-word substring hits. */
    fun tokenMatchesGlued(nameStem: String, queryToken: String): Boolean {
        if (queryToken.isBlank()) return false
        val stem = nameStem.lowercase()
        val q = queryToken.lowercase()
        if (stem == q) return true
        return stemTokens(stem).any { tokenMatchesGluedWord(it, q) }
    }

    fun tokenMatchesFilenameToken(nameToken: String, queryToken: String): Boolean {
        if (queryToken.isBlank()) return false
        val nt = nameToken.lowercase()
        val q = queryToken.lowercase()
        if (nt == q) return true
        return if (q.length >= 3) {
            textContainsToken(nt, q)
        } else {
            tokenMatchesGluedWord(nt, q)
        }
    }

    fun tokenMatchesInFields(
        nameStem: String,
        meta: String,
        content: String,
        entities: String,
        token: String
    ): Boolean =
        textContainsToken(nameStem, token) ||
            textContainsToken(meta, token) ||
            textContainsToken(content, token) ||
            textContainsToken(entities, token) ||
            tokenMatchesGlued(nameStem, token)

    fun allTokensMatchStrict(
        nameStem: String,
        meta: String,
        content: String,
        entities: String,
        tokens: List<String>
    ): Boolean {
        if (tokens.isEmpty()) return false
        return tokens.all { tokenMatchesInFields(nameStem, meta, content, entities, it) }
    }

    fun allTokensMatchRelaxed(nameStem: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        return tokens.all { tokenMatchesGlued(nameStem, it) }
    }

    /** How many query tokens appear in filename / metadata / content (0..1). */
    fun tokenMatchRatio(
        nameStem: String,
        meta: String,
        content: String,
        tokens: List<String>,
        entities: String = ""
    ): Float {
        if (tokens.isEmpty()) return 1f
        val matched = tokens.count { t ->
            tokenMatchesInFields(nameStem, meta, content, entities, t)
        }
        return matched.toFloat() / tokens.size
    }

    fun filenameCoversAllTokens(nameStem: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val nameTokens = stemTokens(nameStem)
        return tokens.all { q ->
            nameTokens.any { nt -> tokenMatchesFilenameToken(nt, q) }
        }
    }

    /** Boost score when query tokens cover filename stem tokens. */
    fun filenameCoverageBoost(nameTokens: List<String>, queryTokens: List<String>): Float {
        if (nameTokens.isEmpty() || queryTokens.isEmpty()) return 0f
        val matchedInName = nameTokens.count { nt ->
            queryTokens.any { q -> tokenMatchesFilenameToken(nt, q) }
        }
        if (matchedInName == 0) return 0f

        var boost = (matchedInName.toFloat() / nameTokens.size) * SearchWeights.FILENAME_COVERAGE_PER_TOKEN
        if (nameTokens.all { nt -> queryTokens.any { q -> tokenMatchesFilenameToken(nt, q) } }) {
            boost += SearchWeights.FILENAME_COVERAGE_FULL_BONUS
        }
        return boost
    }

    fun filenameCoverageRatio(nameTokens: List<String>, queryTokens: List<String>): Float {
        if (nameTokens.isEmpty() || queryTokens.isEmpty()) return 0f
        val matched = nameTokens.count { nt ->
            queryTokens.any { q -> tokenMatchesFilenameToken(nt, q) }
        }
        return matched.toFloat() / nameTokens.size
    }

    fun queryTokenInFilename(nameStem: String, queryTokens: List<String>): Boolean =
        queryTokens.any { textContainsToken(nameStem, it) || tokenMatchesGlued(nameStem, it) }

    /**
     * True when the user is searching for a specific document by title/filename
     * (e.g. "Project Certificate_Mudith July 2023" → that PDF).
     */
    fun queryMatchesFilenameStem(nameStem: String, queryTokens: List<String>): Boolean {
        val core = queryTokens.filter { it.length > 1 }
        if (core.size < SearchWeights.FILENAME_QUERY_MIN_CORE_TOKENS || nameStem.isBlank()) {
            return false
        }
        if (!filenameCoversAllTokens(nameStem, core)) return false

        val nameTokens = stemTokens(nameStem)
        if (nameTokens.isEmpty()) return false

        val nameMatched = nameTokens.count { nt ->
            core.any { q -> tokenMatchesFilenameToken(nt, q) }
        }
        val nameCoverage = nameMatched.toFloat() / nameTokens.size
        if (nameCoverage < SearchWeights.FILENAME_QUERY_MIN_TOKEN_COVERAGE) return false

        val queryMatched = core.count { t ->
            textContainsToken(nameStem, t) || tokenMatchesGlued(nameStem, t)
        }
        return queryMatched.toFloat() / core.size >= SearchWeights.FILENAME_QUERY_MIN_TOKEN_COVERAGE
    }

    fun hasPeriodInName(nameStem: String, periodHints: List<String>): Boolean {
        if (periodHints.isEmpty()) return true
        val lower = nameStem.lowercase()
        return periodHints.any { hint ->
            textContainsToken(lower, hint.lowercase()) ||
                PERIOD_IN_NAME.containsMatchIn(lower)
        }
    }

    /**
     * Multiplier applied to final score (reranker stage only).
     * Uses [coreTokens] only — conversational stop words must not trigger penalties.
     */
    fun specificityMultiplier(
        nameStem: String,
        meta: String,
        content: String,
        tokens: List<String>,
        periodHints: List<String>,
        entities: String = ""
    ): Float {
        val core = MultilingualBridge.coreTokensFromList(tokens)
            .ifEmpty { tokens }
        if (core.isEmpty() && periodHints.isEmpty()) return 1f

        var mult = 1f
        val ratio = tokenMatchRatio(nameStem, meta, content, core, entities)
        val strongNameHit = MultilingualBridge.hasStrongFilenameHit(nameStem, core)

        when {
            ratio >= 1f -> mult *= SearchWeights.SPEC_RATIO_FULL_MULT
            strongNameHit -> mult *= SearchWeights.SPEC_STRONG_NAME_MULT
            ratio >= 0.5f && core.size >= 2 -> mult *= ratio.coerceAtLeast(SearchWeights.SPEC_RATIO_PARTIAL_FLOOR)
            core.size >= 2 -> mult *= ratio.coerceAtLeast(SearchWeights.SPEC_RATIO_WEAK_FLOOR)
        }

        if (periodHints.isNotEmpty()) {
            mult *= if (hasPeriodInName(nameStem, periodHints)) {
                SearchWeights.SPEC_PERIOD_HIT_MULT
            } else {
                SOFT_MISMATCH_PENALTY
            }
        }

        if (core.size >= 2 && filenameCoversAllTokens(nameStem, core)) {
            mult *= SearchWeights.SPEC_FILENAME_COVER_MULT
        }

        return mult.coerceAtMost(SearchWeights.SPECIFICITY_MULT_CAP)
    }
}
