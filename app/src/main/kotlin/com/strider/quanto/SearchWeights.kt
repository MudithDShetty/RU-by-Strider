package com.strider.quanto

/**
 * Tunable search / retrieval constants — single source of truth.
 * Change values here only after golden-query eval (see [SearchEval]).
 *
 * KNOWN_LIMITATIONS (v1):
 * - Tokens 1–2 chars match only via stem-token equality or prefix/suffix (e.g. q2, v2).
 * - No camelCase splitting, edit distance, or mid-word substring for tokens ≥3 chars.
 * - Glued filenames (q2budget) use relaxed tier only when strict tier yields too few hits.
 */
object SearchWeights {

    // ── RRF fusion ──────────────────────────────────────────────────────────
    const val RRF_K = 60
    const val LEXICAL_RRF_WEIGHT = 1.5f
    const val DENSE_RRF_WEIGHT = 1f

    // ── Fallback triggers ([SearchPipeline]) ────────────────────────────────
    const val LOW_CONFIDENCE_SCORE = 0.22f
    const val WEAK_LEXICAL_SCORE = 12f
    const val WEAK_DENSE_SCORE = 0.38f
    const val FALLBACK_SCORE_MARGIN = 0.03f

    // ── Multiplier chain ────────────────────────────────────────────────────
    const val MULTIPLIER_FLOOR = 0.15f
    const val SOFT_MISMATCH_PENALTY = 0.35f
    const val SPECIFICITY_MULT_CAP = 4f

    // ── Specificity stack ([QueryScoring.specificityMultiplier]) ────────────
    const val SPEC_RATIO_FULL_MULT = 2.0f
    const val SPEC_STRONG_NAME_MULT = 1.8f
    const val SPEC_RATIO_PARTIAL_FLOOR = 0.6f
    const val SPEC_RATIO_WEAK_FLOOR = 0.4f
    const val SPEC_PERIOD_HIT_MULT = 2.5f
    const val SPEC_FILENAME_COVER_MULT = 2.0f

    // ── Filename coverage additive boost ────────────────────────────────────
    const val FILENAME_COVERAGE_PER_TOKEN = 80f
    const val FILENAME_COVERAGE_FULL_BONUS = 100f

    // ── Lexical field scores ([LexicalSearch]) ─────────────────────────────
    const val LEX_NAME_EXACT = 100f
    const val LEX_NAME_EDGE = 80f
    const val LEX_NAME_CONTAINS = 50f
    const val LEX_EXT_MATCH = 40f
    const val LEX_FOLDER = 15f
    const val LEX_META = 8f
    const val LEX_CONTENT = 35f
    const val LEX_ENTITY = 55f
    const val LEX_SYNONYM_TOKEN_WEIGHT = 0.65f
    const val LEX_PERSON_NAME_FULL = 120f
    const val LEX_PERSON_NAME_PER_HIT = 40f
    const val LEX_PERIOD_HIT = 90f
    const val LEX_SYNONYM_NAME = 4f
    const val LEX_SYNONYM_META = 2f
    const val MAX_SYNONYM_TOKENS = 8

    // ── Lexical type filter ─────────────────────────────────────────────────
    const val LEX_TYPE_MATCH_MULT = 1.8f
    const val LEX_MEDIA_MATCH_MULT = 1.5f

    // ── Shared signal multipliers (reranker stage only for category/time) ─
    const val CATEGORY_MATCH_MULT = 1.12f
    const val TIME_BUCKET_MULT = 1.08f

    // ── Reranker ([GraniteReranker]) ────────────────────────────────────────
    const val RERANK_PERSON_LEX = 0.78f
    const val RERANK_PERSON_DENSE = 0.22f
    const val RERANK_PERIOD_LEX = 0.72f
    const val RERANK_PERIOD_DENSE = 0.28f
    const val RERANK_STRONG_LEX = 0.55f
    const val RERANK_STRONG_DENSE = 0.45f
    const val RERANK_WEAK_LEX = 0.10f
    const val RERANK_WEAK_DENSE = 0.90f
    const val RERANK_DEFAULT_LEX = 0.30f
    const val RERANK_DEFAULT_DENSE = 0.70f
    const val RERANK_PERIOD_LEX_NORM_MIN = 0.25f
    const val RERANK_STRONG_LEX_NORM_MIN = 0.65f
    const val RERANK_WEAK_LEX_NORM_MAX = 0.12f
    const val RERANK_OVERLAP_WEIGHT = 0.25f
    const val RERANK_NAME_COVERAGE_WEIGHT = 0.20f
    const val RERANK_ALL_TOKENS_IN_NAME_BONUS = 0.18f
    const val RERANK_ENTITY_FULL_BONUS = 0.28f
    const val RERANK_ENTITY_PARTIAL_MULT = 0.22f
    const val RERANK_PERSON_ENTITY_BONUS = 0.12f
    const val RERANK_LEX_NORM_RESCUE_MAX = 0.08f
    const val RERANK_DENSE_RANK_BONUS_TOP5 = 0.07f
    const val RERANK_DENSE_RANK_BONUS_TOP20 = 0.04f
    const val RERANK_DENSE_RANK_BONUS_TOP100 = 0.02f

    /** Title/filename search — query closely matches file stem (e.g. pasted doc name). */
    const val RERANK_FILENAME_QUERY_LEX = 0.62f
    const val RERANK_FILENAME_QUERY_DENSE = 0.38f
    const val RERANK_FILENAME_QUERY_BONUS = 0.20f
    const val FILENAME_QUERY_MIN_TOKEN_COVERAGE = 0.85f
    const val FILENAME_QUERY_MIN_CORE_TOKENS = 2

    /** Strong dense + moderate lexical — hybrid middle band (dense #8, lexical #25). */
    const val RERANK_DENSE_RESCUE_MIN_SCORE = 0.55f
    const val RERANK_DENSE_STRONG_MODERATE_LEX_BONUS = 0.06f
    const val RERANK_DENSE_GOOD_MODERATE_LEX_BONUS = 0.03f

    // ── SQL post-filter ─────────────────────────────────────────────────────
    const val SQL_POST_FILTER_RELAXED_MIN = 3
}
