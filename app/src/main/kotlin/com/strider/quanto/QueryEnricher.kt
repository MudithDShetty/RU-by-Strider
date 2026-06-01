package com.strider.quanto

import android.util.Log

private const val TAG = "QueryEnricher"

// ─────────────────────────────────────────────
// Time hint extraction
// ─────────────────────────────────────────────

private val TIME_HINTS = mapOf(
    // Recent
    listOf("today", "tonight", "now", "latest", "recent", "just", "new")
            to "today this-week",
    // This week
    listOf("this week", "week", "weekly", "few days")
            to "this-week",
    // This month
    listOf("this month", "month", "monthly", "lately", "recently")
            to "this-month",
    // Last quarter
    listOf("last quarter", "quarter", "3 months", "three months")
            to "last-quarter",
    // This year
    listOf("this year", "year", "annual", "yearly", "2024", "2025", "2026")
            to "this-year",
    // Older
    listOf("last year", "old", "older", "archive", "previous", "past", "ago",
        "2020", "2021", "2022", "2023")
            to "older"
)

fun extractTimeHint(query: String): String? {
    val lower = query.lowercase()
    for ((triggers, bucket) in TIME_HINTS) {
        if (triggers.any { lower.contains(it) }) return bucket
    }
    return null
}

// ─────────────────────────────────────────────
// Category hint extraction from query
// ─────────────────────────────────────────────

private val CATEGORY_QUERY_SIGNALS = mapOf(
    Category.IDENTITY to listOf(
        "resume", "cv", "passport", "aadhaar", "aadhar", "pan card", "pan",
        "visa", "license", "certificate", "degree", "marksheet", "id proof",
        "insurance", "tax", "itr", "form 16", "birth certificate", "voter",
        "driving license", "kyc", "identity", "id"
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
        "programming", "code", "python", "java", "math", "science", "history"
    ),
    Category.PERSONAL to listOf(
        "vacation", "trip", "holiday", "family", "birthday", "wedding",
        "party", "friend", "diary", "memory", "memories", "selfie",
        "photo", "picture", "travel", "beach", "festival", "celebration",
        "personal", "my ", "mine"
    ),
    Category.MEDIA to listOf(
        "music", "song", "playlist", "album", "movie", "film", "video",
        "audio", "podcast", "show", "series", "episode", "track", "mix",
        "watch", "listen", "stream", "download"
    )
)

fun extractCategoryHints(query: String): List<Category> {
    val lower = query.lowercase()
    val scores = mutableMapOf<Category, Int>()

    for ((cat, signals) in CATEGORY_QUERY_SIGNALS) {
        var score = 0
        for (signal in signals) {
            if (lower.contains(signal)) score++
        }
        if (score > 0) scores[cat] = score
    }

    return scores.entries
        .sortedByDescending { it.value }
        .take(2)
        .map { it.key }
}

// ─────────────────────────────────────────────
// Synonym expansion
// ─────────────────────────────────────────────

private val SYNONYMS = mapOf(
    // Identity
    "resume"        to "cv biodata portfolio experience skills",
    "cv"            to "resume biodata portfolio",
    "passport"      to "travel document identity international",
    "aadhaar"       to "aadhar uid identity government",
    "pan"           to "pan card tax identity permanent account",
    "tax"           to "itr income tax return form16 tds",
    "certificate"   to "degree diploma marksheet qualification",
    "insurance"     to "policy coverage premium health life",

    // Work
    "invoice"       to "bill receipt payment amount due",
    "report"        to "analysis summary findings data results",
    "meeting"       to "minutes agenda discussion notes conference call",
    "budget"        to "finance money expense cost revenue",
    "salary"        to "payslip payroll compensation ctc package",
    "contract"      to "agreement terms conditions legal document",
    "presentation"  to "slides deck ppt pitch proposal",
    "project"       to "work task assignment deliverable milestone",

    // Education
    "notes"         to "study lecture class subject revision",
    "tutorial"      to "guide learn how-to step instructions",
    "assignment"    to "homework task submission project work",
    "exam"          to "test paper question answer result grade",
    "book"          to "textbook reference material reading pdf",
    "python"        to "programming code script software development",
    "code"          to "programming script software development function",
    "programming"   to "code script software development algorithm",

    // Personal
    "photo"         to "picture image selfie memory moment",
    "photos"        to "pictures images selfies memories moments",
    "vacation"      to "holiday trip travel leisure tourism",
    "trip"          to "travel vacation journey tour visit",
    "birthday"      to "celebration party anniversary event",
    "family"        to "personal home memories together",
    "wedding"       to "marriage ceremony celebration event",
    "memories"      to "photos pictures moments personal",

    // Media
    "music"         to "song audio playlist track album",
    "song"          to "music audio track mp3",
    "playlist"      to "music songs collection album mix",
    "movie"         to "film video watch entertainment",
    "video"         to "film movie clip recording",
    "podcast"       to "audio show episode interview",

    // General
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
)

fun expandWithSynonyms(query: String): String {
    val lower = query.lowercase()
    val expansions = mutableListOf<String>()

    for ((word, synonyms) in SYNONYMS) {
        if (lower.contains(word)) {
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
    listOf("photo", "picture", "image", "selfie", "pic")
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

fun extractTypeHint(query: String): String? {
    val lower = query.lowercase()
    for ((triggers, label) in TYPE_HINTS) {
        if (triggers.any { lower.contains(it) }) return label
    }
    return null
}

// ─────────────────────────────────────────────
// Enriched query data class
// ─────────────────────────────────────────────

data class EnrichedQuery(
    val rawQuery: String,
    val enrichedString: String,         // what gets embedded
    val categoryHints: List<Category>,  // for bucket routing
    val timeHint: String?,
    val typeHint: String?
)

// ─────────────────────────────────────────────
// Main enricher function
// ─────────────────────────────────────────────

fun enrichQuery(rawQuery: String): EnrichedQuery {
    val categoryHints = extractCategoryHints(rawQuery)
    val timeHint      = extractTimeHint(rawQuery)
    val typeHint      = extractTypeHint(rawQuery)
    val expanded      = expandWithSynonyms(rawQuery)

    val parts = mutableListOf<String>()
    parts.add(expanded)
    if (typeHint != null)      parts.add(typeHint)
    if (categoryHints.isNotEmpty()) parts.add(categoryHints.joinToString(" ") { it.label })
    if (timeHint != null)      parts.add(timeHint)

    val enrichedString = parts
        .filter { it.isNotBlank() }
        .joinToString(" | ")

    Log.d(TAG, "Raw query    : $rawQuery")
    Log.d(TAG, "Enriched     : $enrichedString")
    Log.d(TAG, "Categories   : $categoryHints")
    Log.d(TAG, "Time hint    : $timeHint")
    Log.d(TAG, "Type hint    : $typeHint")

    return EnrichedQuery(
        rawQuery       = rawQuery,
        enrichedString = enrichedString,
        categoryHints  = categoryHints,
        timeHint       = timeHint,
        typeHint       = typeHint
    )
}