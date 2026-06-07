package com.strider.quanto

/** Shared token-matching logic for lexical + rerank stages. */
object QueryScoring {

    /** Minimum fraction of base score retained after multiplier chains — prevents silent burial. */
    const val MULTIPLIER_FLOOR = 0.15f

    /** Soft penalty when type/language/period detectors misfire — was 0.05–0.08, far too aggressive. */
    const val SOFT_MISMATCH_PENALTY = 0.35f

    private val PERIOD_IN_NAME = Regex("q[1-4]")

    fun filenameStem(name: String): String =
        name.substringBeforeLast(".")
            .lowercase()
            .replace(Regex("[_\\-.]"), " ")
            .trim()

    fun tokenize(text: String): List<String> = MultilingualBridge.tokenize(text)

    /** Whole-word match for tokens ≥3 chars; shorter tokens fall back to contains. */
    fun textContainsToken(text: String, token: String): Boolean {
        if (token.isBlank()) return false
        return if (token.length >= 3) {
            Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(text)
        } else {
            text.contains(token)
        }
    }

    /** Apply chained multipliers but never reduce below [MULTIPLIER_FLOOR] of base score. */
    fun applyMultiplierChain(baseScore: Float, vararg multipliers: Float): Float {
        if (baseScore <= 0f) return 0f
        val combined = multipliers.fold(1f) { acc, m -> acc * m }
        return baseScore * combined.coerceAtLeast(MULTIPLIER_FLOOR)
    }

    /** How many query tokens appear in filename / metadata / content (0..1). */
    fun tokenMatchRatio(
        nameStem: String,
        meta: String,
        content: String,
        tokens: List<String>
    ): Float {
        if (tokens.isEmpty()) return 1f
        val matched = tokens.count { t ->
            nameStem.contains(t) || meta.contains(t) || content.contains(t)
        }
        return matched.toFloat() / tokens.size
    }

    fun filenameCoversAllTokens(nameStem: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val nameTokens = nameStem.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.all { q ->
            nameTokens.any { nt -> nt == q || nt.contains(q) || q.contains(nt) }
        }
    }

    fun hasPeriodInName(nameStem: String, periodHints: List<String>): Boolean {
        if (periodHints.isEmpty()) return true
        val lower = nameStem.lowercase()
        return periodHints.any { lower.contains(it.lowercase()) } ||
            PERIOD_IN_NAME.containsMatchIn(lower)
    }

    /**
     * Multiplier applied to final score.
     * Uses [coreTokens] only — conversational stop words must not trigger penalties.
     */
    fun specificityMultiplier(
        nameStem: String,
        meta: String,
        content: String,
        tokens: List<String>,
        periodHints: List<String>
    ): Float {
        val core = MultilingualBridge.coreTokensFromList(tokens)
            .ifEmpty { tokens }
        if (core.isEmpty() && periodHints.isEmpty()) return 1f

        var mult = 1f
        val ratio = tokenMatchRatio(nameStem, meta, content, core)
        val strongNameHit = MultilingualBridge.hasStrongFilenameHit(nameStem, core)

        when {
            ratio >= 1f -> mult *= 2.0f
            strongNameHit -> mult *= 1.8f
            ratio >= 0.5f && core.size >= 2 -> mult *= ratio.coerceAtLeast(0.6f)
            core.size >= 2 -> mult *= ratio.coerceAtLeast(0.4f)
        }

        if (periodHints.isNotEmpty()) {
            mult *= if (hasPeriodInName(nameStem, periodHints)) 2.5f else SOFT_MISMATCH_PENALTY
        }

        if (core.size >= 2 && filenameCoversAllTokens(nameStem, core)) {
            mult *= 2.0f
        }

        return mult
    }
}
