package com.strider.ru

/**
 * Party/person names from unstructured PDF body text (NDAs, contracts).
 * Separate from [extractOwnerNames] which only handles labeled ID fields.
 */
private val BETWEEN_PARTIES = Regex(
    """(?i)\bbetween\s+([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})\s+and\s+([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)
private val PARTY_LABEL = Regex(
    """(?i)\bparty\s+([AB12])\s*[:\-]?\s*([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)
private val BY_SIGNATURE = Regex(
    """(?i)\b(?:by|signed\s+by|signature\s+of)\s*[:\-]?\s*([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)
private val EMAIL_LOCAL = Regex(
    """\b([A-Za-z\u0900-\u097f][A-Za-z0-9\u0900-\u097f._-]{1,30})@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""
)
private val HEREINAFTER = Regex(
    """(?i)\(([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,40})\)\s*(?:hereinafter|hereafter)"""
)

private val ENTITY_STOPWORDS = setOf(
    "party", "parties", "agreement", "nda", "company", "corporation", "limited",
    "ltd", "inc", "llc", "the", "and", "between", "hereinafter", "whereas",
    "confidential", "disclosure", "document", "quantoo", "quantum"
)

fun extractPartyEntitiesFromText(text: String, maxEntities: Int = 12): List<String> {
    if (text.isBlank()) return emptyList()

    val names = linkedSetOf<String>()

    BETWEEN_PARTIES.findAll(text).forEach { m ->
        addPersonName(names, m.groupValues[1])
        addPersonName(names, m.groupValues[2])
    }
    PARTY_LABEL.findAll(text).forEach { m ->
        addPersonName(names, m.groupValues[2])
    }
    BY_SIGNATURE.findAll(text).forEach { m ->
        addPersonName(names, m.groupValues[1])
    }
    HEREINAFTER.findAll(text).forEach { m ->
        addPersonName(names, m.groupValues[1])
    }
    EMAIL_LOCAL.findAll(text).forEach { m ->
        val local = m.groupValues[1]
        if (isNameLikeToken(local)) names.add(local.lowercase())
    }

    return names.take(maxEntities)
}

fun buildExtractedEntities(
    rawText: String?,
    ownerEntities: List<NameEntity>
): List<String> {
    val result = linkedSetOf<String>()
    if (!rawText.isNullOrBlank()) {
        result.addAll(extractPartyEntitiesFromText(rawText))
    }
    ownerEntities.flatMap { it.tokens }
        .filter { isNameLikeToken(it) }
        .forEach { result.add(it.lowercase()) }
    return result.toList()
}

/** Strip party/person tokens from contextual keyword text so embed budget stays thematic. */
fun stripEntityTokensFromKeywords(keywords: String, entities: List<String>): String {
    if (keywords.isBlank() || entities.isEmpty()) return keywords
    val drop = entities.map { it.lowercase() }.toSet()
    return keywords.split(Regex("\\s+"))
        .filter { it.isNotBlank() && it.lowercase() !in drop }
        .joinToString(" ")
}

private fun addPersonName(names: MutableSet<String>, raw: String) {
    val cleaned = raw.trim()
        .split(Regex("\\s*(?:,|hereinafter|hereafter|\\()")).first()
        .trim()
    val tokens = UserProfile.tokenizeName(cleaned)
    if (tokens.isEmpty()) return
    if (tokens.all { it.lowercase() in ENTITY_STOPWORDS }) return
    if (!tokens.any { isNameLikeToken(it) }) return
    tokens.filter { it.lowercase() !in ENTITY_STOPWORDS && isNameLikeToken(it) }
        .forEach { names.add(it.lowercase()) }
    if (tokens.size >= 2) {
        val full = tokens.joinToString(" ")
        if (full.length in 3..40) names.add(full.lowercase())
    }
}

fun isNameLikeToken(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 2 || trimmed.length > 50) return false
    if (trimmed.matches(Regex("^\\d+$"))) return false
    if (trimmed.lowercase() in ENTITY_STOPWORDS) return false
    val letters = trimmed.count { it.isLetter() || it in '\u0900'..'\u097f' }
    return letters >= trimmed.length / 2
}
