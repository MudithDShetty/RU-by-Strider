package com.strider.ru

import android.util.Log

private const val TAG = "QueryEnricher"

// ─────────────────────────────────────────────
// Time hint extraction
// ─────────────────────────────────────────────

private fun buildTimeHints(): List<Pair<List<String>, String>> {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return listOf(
        listOf("today", "tonight", "now", "latest", "just", "new") to "today",
        listOf("this week", "weekly", "few days") to "this-week",
        listOf("this month", "monthly", "lately", "recently") to "this-month",
        listOf("last quarter", "quarter", "3 months", "three months") to "last-quarter",
        listOf("this year", "annual", "yearly", year.toString()) to "this-year",
        listOf("last year", "old", "older", "archive", "previous", "past", "ago",
            (year - 1).toString(), (year - 2).toString(), (year - 3).toString()) to "older"
    )
}

fun extractTimeHint(query: String): String? {
    val lower = query.lowercase()
    for ((triggers, bucket) in buildTimeHints()) {
        for (trigger in triggers) {
            if (trigger.contains(' ')) {
                if (lower.contains(trigger)) return bucket
            } else if (Regex("\\b${Regex.escape(trigger)}\\b").containsMatchIn(lower)) {
                return bucket
            }
        }
    }
    return null
}

// ─────────────────────────────────────────────
// Category hint extraction from query
// ─────────────────────────────────────────────

private val CATEGORY_QUERY_SIGNALS = mapOf(
    Category.IDENTITY to listOf(
        "resume", "cv", "passport", "aadhaar", "aadhar", "pan card", "pan", "pancard",
        "visa", "license", "certificate", "degree", "marksheet", "id proof",
        "insurance", "tax", "itr", "form 16", "birth certificate", "voter",
        "driving license", "kyc", "identity", "id", "return file", "proof"
    ),
    Category.WORK to listOf(
        "invoice", "report", "meeting", "minutes", "proposal", "contract",
        "budget", "salary", "offer letter", "office", "client", "vendor",
        "presentation", "project", "work", "business", "company", "professional",
        "expense", "receipt", "purchase order", "quotation", "tender"
    ),
    Category.EDUCATION to listOf(
        "tutorial", "course", "notes", "study", "lecture", "assignment",
        "homework", "exam", "book", "learn", "class", "college", "school",
        "university", "subject", "revision", "question paper", "solution",
        "programming", "code", "python", "java", "math", "science", "history",
        "padhai"
    ),
    Category.PERSONAL to listOf(
        "vacation", "trip", "holiday", "family", "birthday", "geburtstag", "anniversaire",
        "cumpleanos", "compleanno", "aniversario", "wedding", "hochzeit", "mariage",
        "party", "friend", "diary", "memory", "memories", "selfie",
        "photo", "picture", "fotos", "foto", "bilder", "travel", "beach", "festival",
        "celebration", "personal", "ghar", "urlaub", "reise", "vacances", "ferias"
    ),
    Category.MEDIA to listOf(
        "music", "song", "playlist", "album", "movie", "film", "video",
        "audio", "podcast", "show", "series", "episode", "track", "mix",
        "watch", "listen", "stream", "download", "fitness"
    )
)

fun extractCategoryHints(query: String): List<Category> {
    val lower = query.lowercase()
    val tokens = MultilingualBridge.tokenize(lower).toSet()
    val scores = mutableMapOf<Category, Int>()

    for ((cat, signals) in CATEGORY_QUERY_SIGNALS) {
        var score = 0
        for (signal in signals) {
            if (signal.contains(' ')) {
                if (lower.contains(signal)) score++
            } else if (signal in tokens) {
                score++
            }
        }
        if (score > 0) scores[cat] = score
    }

    return scores.entries
        .sortedByDescending { it.value }
        .take(2)
        .map { it.key }
}

// ─────────────────────────────────────────────
// Synonym expansion (BM25 branch only)
// ─────────────────────────────────────────────

private val SYNONYMS = mapOf(
    "resume"        to "cv biodata portfolio experience skills",
    "cv"            to "resume biodata portfolio",
    "passport"      to "travel document identity international",
    "aadhaar"       to "aadhar aadhaar uid identity government card",
    "aadhar"        to "aadhaar uid identity government card",
    "pan"           to "pan card tax identity permanent account",
    "tax"           to "itr income tax return form16 tds",
    "certificate"   to "degree diploma marksheet qualification",
    "insurance"     to "policy coverage premium health life",
    "invoice"       to "bill receipt payment amount due",
    "report"        to "analysis summary findings data results",
    "meeting"       to "minutes agenda discussion notes conference call",
    "budget"        to "finance money expense cost revenue bajat",
    "salary"        to "payslip payroll compensation ctc package",
    "contract"      to "agreement terms conditions legal document",
    "presentation"  to "slides deck ppt pitch proposal",
    "project"       to "work task assignment deliverable milestone",
    "notes"         to "study lecture class subject revision",
    "tutorial"      to "guide learn how-to step instructions",
    "assignment"    to "homework task submission project work",
    "exam"          to "test paper question answer result grade",
    "book"          to "textbook reference material reading pdf",
    "python"        to "programming code script software development",
    "code"          to "programming script software development function",
    "programming"   to "code script software development algorithm",
    "photo"         to "photo foto picture image selfie tasveer",
    "photos"        to "pictures images selfies memories moments tasveer",
    "vacation"      to "holiday trip travel leisure tourism",
    "trip"          to "travel vacation journey tour visit",
    "birthday"      to "celebration party anniversary event",
    "family"        to "personal home memories together ghar",
    "wedding"       to "marriage ceremony celebration event",
    "memories"      to "photos pictures moments personal",
    "music"         to "song audio playlist track album",
    "song"          to "music audio track mp3",
    "playlist"      to "music songs collection album mix",
    "movie"         to "film video watch entertainment",
    "video"         to "film movie clip recording",
    "podcast"       to "audio show episode interview",
    "document"      to "file text pdf word",
    "file"          to "document data record",
    "download"      to "saved file received",
    "old"           to "archive previous past older",
    "important"     to "critical key essential priority",
    "gym"           to "fitness workout exercise training health",
    "fitness"       to "gym workout exercise health training",
    "workout"       to "gym fitness exercise training",
    "cooking"       to "recipe food kitchen meal",
    "recipe"        to "cooking food ingredients meal preparation",
    "travel"        to "trip vacation holiday journey tour",
    "paisa"         to "money rupee payment amount finance budget",
    "kaam"          to "work office project task document",
    "ghar"          to "home personal family house",
    "padhai"        to "study education notes college school university"
)

fun expandWithSynonyms(query: String): String {
    val lower = query.lowercase()
    val tokens = MultilingualBridge.tokenize(lower).toSet()
    val expansions = mutableListOf<String>()

    for ((word, synonyms) in SYNONYMS) {
        if (word in tokens) {
            expansions.add(synonyms)
        }
    }

    return if (expansions.isEmpty()) query
    else "$query ${expansions.joinToString(" ")}"
}

// ─────────────────────────────────────────────
// Type hint extraction
// ─────────────────────────────────────────────

private val TYPE_HINTS = mapOf(
    listOf("photo", "picture", "image", "selfie", "pic", "tasveer", "fotos", "foto", "bilder", "bild")
            to "photo image",
    listOf("video", "movie", "film", "clip", "recording")
            to "video",
    listOf("music", "song", "audio", "track", "playlist", "mp3")
            to "audio music",
    listOf("document", "doc", "file", "pdf", "word")
            to "document",
    listOf("spreadsheet", "excel", "sheet", "csv", "data")
            to "spreadsheet data",
    listOf("presentation", "slides", "ppt", "deck")
            to "presentation slides",
    listOf("code", "script", "program", "app")
            to "code",
    listOf("zip", "archive", "compressed", "rar")
            to "archive compressed"
)

fun extractTypeHint(query: String, cleanQuery: String): String? {
    val lower = query.lowercase()
    val cleanTokens = cleanQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
    for ((triggers, label) in TYPE_HINTS) {
        for (trigger in triggers) {
            if (!Regex("\\b${Regex.escape(trigger)}\\b").containsMatchIn(lower)) continue
            // "file"/"document" are usually speech filler when other keywords exist
            if (trigger in setOf("file", "document", "doc") && cleanTokens.size >= 2) continue
            return label
        }
    }
    return null
}

// ─────────────────────────────────────────────
// Clean query for embedding (no synonym expansion)
// ─────────────────────────────────────────────

/** Conversational / command words stripped before embedding & lexical tokenization. */
private val FILLER_WORDS = setOf(
    // English request verbs
    "find", "show", "get", "search", "look", "fetch", "give", "bring",
    "tell", "open", "load", "pull", "grab", "list",
    // Pronouns / determiners (possessives like "my"/"mera" are kept — see POSSESSIVE_KEEP_WORDS)
    "me", "the", "a", "an", "some", "any", "all", "this", "that",
    "these", "those", "it", "its", "your", "our", "you", "i", "we",
    // Politeness / modals
    "please", "can", "could", "would", "should", "will", "want", "need",
    "help", "also", "just", "really", "actually", "maybe",
    // Prepositions & glue words (not useful for file matching)
    "for", "about", "of", "on", "in", "at", "to", "from", "with", "by",
    "into", "via", "as", "and", "or", "but", "so", "if", "when", "where",
    // Generic nouns that appear in natural speech but aren't file signals
    "info", "information", "details", "detail", "stuff", "thing", "things",
    "file", "files", "document", "documents", "doc", "data", "record",
    // Hindi / Hinglish (mera/meri/mere kept for owner intent)
    "mujhe", "dhundo", "dikhao", "dedo",
    "chahiye", "karo", "wala", "wali", "hai", "hain", "tha", "thi",
    "jaldi", "abhi", "yahan", "wahan", "kya", "konsa", "batao", "bata"
)

/** Action intents — stripped from search tokens, trigger share/send flow. */
private val ACTION_INTENT_WORDS = setOf(
    "share", "send", "forward", "export", "upload", "attach", "transfer", "deliver", "post"
)

private val SHARE_TARGET_WORDS = mapOf(
    "whatsapp" to "whatsapp",
    "wa"       to "whatsapp",
    "telegram" to "telegram",
    "email"    to "email",
    "gmail"    to "email",
    "mail"     to "email",
    "drive"    to "drive"
)

fun extractActionIntent(query: String): String? {
    val lower = query.lowercase()
    return ACTION_INTENT_WORDS.firstOrNull { word ->
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
    }
}

fun extractShareTarget(query: String): String? {
    val lower = query.lowercase()
    return SHARE_TARGET_WORDS.entries.firstOrNull { (word, _) ->
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
    }?.value
}

private val PERIOD_PATTERN = Regex("\\bq([1-4])\\b|\\bquarter\\s*([1-4])\\b")

fun extractPeriodHints(query: String): List<String> {
    val lower = query.lowercase()
    val hints = mutableListOf<String>()
    PERIOD_PATTERN.findAll(lower).forEach { match ->
        val q = match.groupValues[1].ifBlank { match.groupValues[2] }
        if (q.isNotBlank()) hints.add("Q$q")
    }
    return hints.distinct()
}

fun buildCleanQuery(rawQuery: String): String {
    val stripped = MultilingualBridge.stripStopWords(
        MultilingualBridge.normalizeColloquial(rawQuery.lowercase())
    )
    return MultilingualBridge.tokenize(stripped)
        .take(10)
        .joinToString(" ")
        .ifBlank { rawQuery.trim().lowercase() }
}

/** Stable token order for bi-encoder input — "mudith certificate" == "certificate mudith". */
fun normalizeTokenOrderForEmbedding(cleanQuery: String): String {
    val tokens = MultilingualBridge.tokenize(cleanQuery)
    if (tokens.size <= 1) return cleanQuery
    return tokens.sorted().joinToString(" ")
}

/** Document/category signal tokens — must not route mixed queries into person-name mode. */
private val DOC_TYPE_QUERY_TOKENS: Set<String> = buildSet {
    CATEGORY_QUERY_SIGNALS.values.flatten().forEach { signal ->
        signal.split(Regex("\\s+")).forEach { add(it) }
    }
}

fun isDocTypeQueryToken(token: String): Boolean =
    token.lowercase() in DOC_TYPE_QUERY_TOKENS

// ─────────────────────────────────────────────
// Query type routing
// ─────────────────────────────────────────────

enum class QueryType {
    GENERAL,
    PERSON_NAME
}

fun resolveQueryType(rawQuery: String, coreTokens: List<String>): QueryType {
    val tokens = coreTokens.ifEmpty {
        MultilingualBridge.coreTokens(buildCleanQuery(rawQuery))
    }.filter { it.length >= 2 }
    if (tokens.any { isDocTypeQueryToken(it) }) return QueryType.GENERAL
    if (tokens.size in 1..2 && tokens.all { isNameLikeToken(it) }) {
        return QueryType.PERSON_NAME
    }
    return QueryType.GENERAL
}

// ─────────────────────────────────────────────
// Enriched query data class
// ─────────────────────────────────────────────

data class EnrichedQuery(
    val rawQuery: String,
    val cleanQueryForEmbedding: String,
    val keywordsForBm25: String,
    val enrichedString: String,
    val categoryHints: List<Category>,
    val timeHint: String?,
    val typeHint: String?,
    val actionIntent: String? = null,
    val shareTarget: String? = null,
    val periodHints: List<String> = emptyList(),
    /** e.g. HINDI from "python notes in hindi" — boosts matching script in results. */
    val languageHint: LanguageHint? = null,
    /** Cross-script + cross-language expanded tokens for lexical matching. */
    val lexicalTokens: List<String> = emptyList(),
    /** Content-bearing tokens after stop-word removal — used for scoring, not filler. */
    val coreTokens: List<String> = emptyList(),
    val ownerIntent: OwnerIntent = OwnerIntent.NONE,
    val ownerTargetTokens: List<String> = emptyList(),
    /**
     * Extra AND token groups for possessive self-queries — e.g. last/middle name + doc keyword.
     * Filename/content SQL runs each group separately so "my pancard" matches "shetty pan.pdf".
     */
    val ownerRetrievalAlternates: List<List<String>> = emptyList(),
    /** First name + doc keyword — primary SQL retrieval pass for possessive self-queries. */
    val ownerRetrievalPrimary: List<String> = emptyList(),
    val queryType: QueryType = QueryType.GENERAL
)

// ─────────────────────────────────────────────
// Owner intent (my / named third-party)
// ─────────────────────────────────────────────

private val POSSESSIVE_MARKERS = POSSESSIVE_KEEP_WORDS

private val NAME_QUERY_BLOCKLIST = buildSet {
    addAll(FILLER_WORDS)
    addAll(ACTION_INTENT_WORDS)
    CATEGORY_QUERY_SIGNALS.values.flatten().flatMap { it.split(Regex("\\s+")) }.forEach { add(it) }
    addAll(listOf(
        "card", "proof", "document", "file", "pdf", "find", "show", "get", "search",
        "please", "kumar", "singh", "sharma", "gupta", "patel", "verma", "khan"
    ))
}

private val POSSESSIVE_NAME_PATTERN = Regex(
    """(?i)\b([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,30})'s\b"""
)
private val FOR_OF_NAME_PATTERN = Regex(
    """(?i)\b(?:for|of)\s+([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,40})"""
)

fun resolveOwnerIntent(rawQuery: String, userNameTokens: List<String>): Pair<OwnerIntent, List<String>> {
    val named = extractNamedOwnerFromQuery(rawQuery, userNameTokens)
    if (named.isNotEmpty()) {
        return OwnerIntent.NAMED to named
    }
    if (hasPossessiveMarker(rawQuery) && userNameTokens.isNotEmpty()) {
        return OwnerIntent.SELF to userNameTokens
    }
    return OwnerIntent.NONE to emptyList()
}

private fun hasPossessiveMarker(query: String): Boolean {
    val lower = query.lowercase()
    return POSSESSIVE_MARKERS.any { word ->
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
    }
}

private fun extractNamedOwnerFromQuery(rawQuery: String, userNameTokens: List<String>): List<String> {
    POSSESSIVE_NAME_PATTERN.find(rawQuery)?.groupValues?.getOrNull(1)?.let { raw ->
        val tokens = UserProfile.tokenizeName(raw)
        if (tokens.isNotEmpty() && !isOnlyUserSelf(tokens, userNameTokens, rawQuery)) {
            return tokens
        }
    }

    FOR_OF_NAME_PATTERN.find(rawQuery)?.groupValues?.getOrNull(1)?.let { raw ->
        val cleaned = raw.trim().split(Regex("\\s+(?:aadhaar|aadhar|pan|passport|visa|card|certificate|resume|cv)\\b", RegexOption.IGNORE_CASE)).first()
        val tokens = UserProfile.tokenizeName(cleaned)
        if (tokens.isNotEmpty()) return tokens
    }

    val lower = rawQuery.lowercase()
    val queryTokens = MultilingualBridge.tokenize(lower)
    val candidate = queryTokens.filter { token ->
        token.length >= 3 &&
            token !in NAME_QUERY_BLOCKLIST &&
            !token.matches(Regex("^\\d+$"))
    }

    if (candidate.isNotEmpty() && candidate.size <= 3) {
        val hasDocSignal = CATEGORY_QUERY_SIGNALS[Category.IDENTITY]?.any { lower.contains(it) } == true ||
            queryTokens.any { it in setOf("aadhaar", "aadhar", "pan", "passport", "visa", "resume", "cv", "certificate") }
        if (hasDocSignal && !hasPossessiveMarker(rawQuery)) {
            return candidate.take(3)
        }
    }

    return emptyList()
}

private fun isOnlyUserSelf(namedTokens: List<String>, userTokens: List<String>, rawQuery: String): Boolean {
    if (userTokens.isEmpty()) return false
    if (!hasPossessiveMarker(rawQuery)) return false
    return namedTokens.toSet() == userTokens.toSet()
}

/** Remove possessive markers ("my", "mera", …) from token lists — they never appear in filenames. */
fun stripPossessiveMarkers(tokens: List<String>): List<String> =
    tokens.filter { it !in POSSESSIVE_MARKERS }

/**
 * When owner intent is SELF, use the user's name for SQL retrieval only.
 * Doc keywords drive ranking, embedding, and rerank ([coreTokens]).
 */
data class SelfOwnerSearchForms(
    val coreTokens: List<String>,
    val embedQueryText: String,
    val retrievalPrimary: List<String>,
    val retrievalAlternates: List<List<String>>
)

fun buildSelfOwnerSearchForms(
    coreTokens: List<String>,
    userNameTokens: List<String>,
    embedFallback: String
): SelfOwnerSearchForms {
    val docTokens = stripPossessiveMarkers(coreTokens).filter { it.length >= 2 }
    val firstName = UserProfile.firstNameToken(userNameTokens)
        ?: return SelfOwnerSearchForms(
            coreTokens = docTokens,
            embedQueryText = docTokens.joinToString(" ").ifBlank { embedFallback },
            retrievalPrimary = emptyList(),
            retrievalAlternates = emptyList()
        )

    val retrievalPrimary = (listOf(firstName) + docTokens).distinct()
    val alternates = mutableListOf<List<String>>()

    UserProfile.alternateIdentityTokens(userNameTokens).forEach { alt ->
        alternates.add((listOf(alt) + docTokens).distinct())
    }

    val primary = UserProfile.primaryIdentityTokens(userNameTokens)
    if (primary.size >= 2) {
        val lastName = primary.last()
        if (lastName != firstName) {
            val lastGroup = (listOf(lastName) + docTokens).distinct()
            if (lastGroup != retrievalPrimary && lastGroup !in alternates) {
                alternates.add(lastGroup)
            }
        }
    }

    val embedText = docTokens.joinToString(" ").ifBlank { embedFallback }
    return SelfOwnerSearchForms(
        coreTokens = docTokens,
        embedQueryText = embedText,
        retrievalPrimary = retrievalPrimary,
        retrievalAlternates = alternates
    )
}

// ─────────────────────────────────────────────
// Main enricher function
// ─────────────────────────────────────────────

fun enrichQuery(rawQuery: String, userNameTokens: List<String> = emptyList()): EnrichedQuery {
    val mlForms       = MultilingualBridge.buildForms(rawQuery)
    val categoryHints = extractCategoryHints(rawQuery)
    val timeHint      = extractTimeHint(rawQuery)
    val baseCleanQuery = buildCleanQuery(mlForms.embedQuery.ifBlank { mlForms.strippedQuery })
    val actionIntent  = extractActionIntent(rawQuery)
    val shareTarget   = extractShareTarget(rawQuery)
    val periodHints   = extractPeriodHints(rawQuery)
    val (ownerIntent, ownerTargetTokens) = resolveOwnerIntent(rawQuery, userNameTokens)

    var coreTokens = mlForms.coreTokens.ifEmpty {
        MultilingualBridge.coreTokens(baseCleanQuery)
    }
    var cleanQuery = baseCleanQuery
    var ownerRetrievalPrimary = emptyList<String>()
    var ownerRetrievalAlternates = emptyList<List<String>>()

    if (ownerIntent == OwnerIntent.SELF && ownerTargetTokens.isNotEmpty()) {
        val selfForms = buildSelfOwnerSearchForms(coreTokens, ownerTargetTokens, baseCleanQuery)
        coreTokens = selfForms.coreTokens
        cleanQuery = selfForms.embedQueryText
        ownerRetrievalPrimary = selfForms.retrievalPrimary
        ownerRetrievalAlternates = selfForms.retrievalAlternates
    }

    val embedQuery    = normalizeTokenOrderForEmbedding(cleanQuery)
    val typeHint      = extractTypeHint(rawQuery, cleanQuery)
    val synonymKw     = expandWithSynonyms(cleanQuery)
    val crossScriptKw = mlForms.lexicalTokens.joinToString(" ")

    val lexicalBase = if (ownerIntent == OwnerIntent.SELF) {
        stripPossessiveMarkers(mlForms.lexicalTokens)
    } else {
        mlForms.lexicalTokens
    }
    val ownerLexical = ownerTargetTokens.joinToString(" ")
    val lexicalWithOwner = (lexicalBase + ownerTargetTokens).distinct()
    val bm25WithOwner = listOf(synonymKw, crossScriptKw, ownerLexical)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    if (BuildConfig.DEBUG) {
        Log.d(TAG, "Raw query    : $rawQuery")
        Log.d(TAG, "Clean embed  : $embedQuery")
        Log.d(TAG, "BM25 keywords: $bm25WithOwner")
        Log.d(TAG, "Language hint: ${mlForms.languageHint}")
        Log.d(TAG, "Lexical tok  : ${lexicalWithOwner.take(8)}")
        Log.d(TAG, "Categories   : $categoryHints")
        Log.d(TAG, "Time hint    : $timeHint")
        Log.d(TAG, "Action intent: $actionIntent")
        Log.d(TAG, "Share target : $shareTarget")
        Log.d(TAG, "Period hints : $periodHints")
        Log.d(TAG, "Owner intent : $ownerIntent target=$ownerTargetTokens")
        Log.d(TAG, "Owner primary: $ownerRetrievalPrimary")
        Log.d(TAG, "Owner alts   : $ownerRetrievalAlternates")
    }

    val queryType = resolveQueryType(rawQuery, coreTokens)
    if (BuildConfig.DEBUG) {
        Log.d(TAG, "Query type    : $queryType")
    }

    return EnrichedQuery(
        rawQuery               = rawQuery,
        cleanQueryForEmbedding = embedQuery,
        keywordsForBm25        = bm25WithOwner,
        enrichedString         = cleanQuery,
        categoryHints          = categoryHints,
        timeHint               = timeHint,
        typeHint               = typeHint,
        actionIntent           = actionIntent,
        shareTarget            = shareTarget,
        periodHints            = periodHints,
        languageHint           = mlForms.languageHint,
        lexicalTokens          = lexicalWithOwner,
        coreTokens             = coreTokens,
        ownerIntent            = ownerIntent,
        ownerTargetTokens      = ownerTargetTokens,
        ownerRetrievalAlternates = ownerRetrievalAlternates,
        ownerRetrievalPrimary  = ownerRetrievalPrimary,
        queryType              = queryType
    )
}
