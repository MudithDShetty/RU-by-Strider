package com.strider.quanto

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "FileMetadata"

// ─────────────────────────────────────────────
// Category enum
// ─────────────────────────────────────────────

enum class Category(val label: String, val keywords: List<String>) {
    IDENTITY("identity", listOf(
        "resume", "cv", "biodata", "passport", "aadhaar", "aadhar", "pan",
        "visa", "license", "licence", "certificate", "marksheet", "degree",
        "id", "identity", "insurance", "policy", "tax", "itr", "form16",
        "birth", "marriage", "voter", "driving", "national", "kyc",
        "document", "proof", "official", "government", "personal"
    )),
    WORK("work", listOf(
        "invoice", "report", "meeting", "minutes", "proposal", "contract",
        "budget", "salary", "offer", "letter", "project", "client", "vendor",
        "presentation", "agenda", "task", "deadline", "kpi", "quarterly",
        "annual", "office", "business", "company", "work", "professional",
        "employee", "hr", "payroll", "expense", "receipt", "purchase",
        "quotation", "tender", "agreement", "memo", "circular", "sales"
    )),
    EDUCATION("education", listOf(
        "tutorial", "course", "notes", "study", "lecture", "assignment",
        "homework", "exam", "syllabus", "chapter", "exercise", "practice",
        "learn", "guide", "book", "reference", "school", "college",
        "university", "class", "subject", "revision", "question", "paper",
        "solution", "answer", "result", "mark", "grade", "semester",
        "programming", "algorithm", "python", "java", "math", "science"
    )),
    PERSONAL("personal", listOf(
        "vacation", "trip", "holiday", "family", "birthday", "wedding",
        "party", "friend", "home", "personal", "diary", "journal", "memory",
        "memories", "selfie", "photo", "picture", "love", "baby", "kid",
        "travel", "beach", "mountain", "festival", "celebration", "gift",
        "goa", "mumbai", "delhi", "fun", "weekend", "outing"
    )),
    MEDIA("media", listOf(
        "music", "song", "playlist", "album", "movie", "film", "video",
        "audio", "podcast", "show", "series", "episode", "track", "mix",
        "wallpaper", "background", "download", "stream", "entertainment"
    )),
    GENERAL("general", emptyList())
}

// ─────────────────────────────────────────────
// Bucket helpers
// ─────────────────────────────────────────────

fun getAgeBucket(lastModified: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastModified)
    return when {
        days <= 1   -> "today"
        days <= 7   -> "this-week"
        days <= 30  -> "this-month"
        days <= 90  -> "last-quarter"
        days <= 365 -> "this-year"
        else        -> "older"
    }
}

fun getSizeBucket(bytes: Long): String = when {
    bytes < 10 * 1024              -> "tiny"
    bytes < 500 * 1024             -> "small"
    bytes < 10 * 1024 * 1024       -> "medium"
    bytes < 100 * 1024 * 1024      -> "large"
    else                           -> "huge"
}

fun getTypeLabel(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg", "png", "gif",
    "webp", "heic", "bmp"          -> "photo image"
    "mp3", "aac", "flac",
    "wav", "ogg", "m4a"            -> "audio music"
    "mp4", "mkv", "avi",
    "mov", "wmv", "3gp"            -> "video"
    "pdf"                          -> "pdf document"
    "doc", "docx"                  -> "word document"
    "xls", "xlsx"                  -> "spreadsheet"
    "ppt", "pptx"                  -> "presentation slides"
    "txt", "md"                    -> "text document"
    "csv"                          -> "data spreadsheet"
    "json", "xml"                  -> "data file"
    "html", "htm"                  -> "webpage"
    "zip", "rar", "7z",
    "tar", "gz"                    -> "archive compressed"
    "apk"                          -> "android app"
    "py"                           -> "python code"
    "js", "ts"                     -> "javascript code"
    "kt", "java"                   -> "kotlin java code"
    "cpp", "c", "h"                -> "cpp code"
    else                           -> "file"
}

// ─────────────────────────────────────────────
// Filename entity recognition
// ─────────────────────────────────────────────

data class FilenameEntities(
    val year: String?,
    val month: String?,
    val quarter: String?,
    val documentNumber: String?,
    val personName: String?,
    val version: String?
)

fun extractFilenameEntities(nameWithoutExt: String): FilenameEntities {
    val lower = nameWithoutExt.lowercase()
    return FilenameEntities(
        year           = Regex("20(2[0-9])").find(lower)?.value,
        month          = Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)").find(lower)?.value,
        quarter        = Regex("q[1-4]").find(lower)?.value?.uppercase(),
        documentNumber = Regex("#?\\b(\\d{3,6})\\b").find(lower)?.value,
        personName     = when {
            lower.startsWith("resume_") || lower.startsWith("cv_") ->
                nameWithoutExt.substringAfter("_").substringBefore("_")
                    .replaceFirstChar { it.uppercase() }
            else -> null
        },
        version = Regex("\\b(v\\d+|final|draft|old|backup|copy)\\b").find(lower)?.value
    )
}

fun entitiesToSentencePart(entities: FilenameEntities): String {
    val parts = mutableListOf<String>()
    entities.personName?.let { parts.add(it) }
    if (entities.month != null && entities.year != null) {
        parts.add("${entities.month} ${entities.year}")
    } else {
        entities.month?.let { parts.add(it) }
        entities.year?.let { parts.add(it) }
    }
    entities.quarter?.let { parts.add(it) }
    entities.documentNumber?.let { parts.add("number $it") }
    entities.version?.let { parts.add("$it version") }
    return parts.joinToString(" ")
}

// ─────────────────────────────────────────────
// Natural language sentence builders
// ─────────────────────────────────────────────

private val MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic",
    "mp3", "aac", "flac", "wav", "m4a", "mp4", "mkv", "avi", "mov"
)

private fun articleFor(label: String): String =
    if (label.firstOrNull()?.let { it in "aeiou" } == true) "An" else "A"

fun buildDocumentSentence(
    file: File,
    contentSnippet: String?,
    categories: List<Category>,
    ageBucket: String,
    entities: FilenameEntities,
    displayName: String? = null
): String {
    val cleanName = displayName ?: file.nameWithoutExtension
        .replace(Regex("[_\\-.]"), " ")
        .trim()
    val catLabel  = categories.firstOrNull()?.label ?: "general"
    val typeLabel = getTypeLabel(file.extension.lowercase())
    val folder    = file.parentFile?.name ?: "storage"
    val agePart   = ageBucket.replace("-", " ")
    val entityPart = entitiesToSentencePart(entities)
    val langPart   = MultilingualBridge.detectDominantLanguage(cleanName)?.let { " in $it" } ?: ""

    // Content first — embedding reads only the first ~64 tokens; junk filenames must not crowd out content.
    val parts = mutableListOf<String>()
    if (!contentSnippet.isNullOrBlank()) parts.add(contentSnippet.trim())
    if (entityPart.isNotBlank()) parts.add(entityPart)
    parts.add("${articleFor(catLabel)} $catLabel $typeLabel named $cleanName$langPart")
    parts.add("last modified $agePart stored in $folder")

    return parts.joinToString(" ").take(400)
}

fun buildMediaSentence(
    file: File,
    contentSnippet: String?,
    categories: List<Category>,
    ageBucket: String,
    entities: FilenameEntities
): String {
    val cleanName = file.nameWithoutExtension
        .replace(Regex("[_\\-.]"), " ")
        .trim()
    val typeLabel = getTypeLabel(file.extension.lowercase())
    val folder    = file.parentFile?.name ?: "storage"
    val agePart   = ageBucket.replace("-", " ")
    val catLabel  = categories.firstOrNull()?.label ?: "personal"
    val entityPart = entitiesToSentencePart(entities)

    val parts = mutableListOf<String>()
    if (!contentSnippet.isNullOrBlank()) parts.add(contentSnippet.trim())
    if (entityPart.isNotBlank()) parts.add(entityPart)
    parts.add("${articleFor(catLabel)} $catLabel $typeLabel file named $cleanName")
    parts.add("in $folder folder modified $agePart")

    return parts.joinToString(" ").take(400)
}

// ─────────────────────────────────────────────
// Filename pattern signals
// ─────────────────────────────────────────────

fun extractFilenameSignals(nameWithoutExt: String): List<String> {
    val signals = mutableListOf<String>()
    val lower = nameWithoutExt.lowercase()
    if (lower.contains(Regex("\\d{4}")))              signals.add("dated")
    if (lower.contains(Regex("v\\d|final|draft")))    signals.add("versioned")
    if (lower == lower.uppercase() && lower.length > 3) signals.add("important")
    if (lower.contains(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)")))
        signals.add("monthly")
    if (lower.contains(Regex("q[1-4]|quarter")))      signals.add("quarterly")
    return signals
}

// ─────────────────────────────────────────────
// Category classifier
// ─────────────────────────────────────────────

private fun textContainsKeyword(text: String, tokens: Set<String>, keyword: String): Boolean {
    if (keyword.contains(' ')) return text.contains(keyword)
    return keyword in tokens
}

fun classifyCategories(file: File, contentSnippet: String?): List<Category> {
    val scores = mutableMapOf<Category, Int>()
    val lower = file.nameWithoutExtension.lowercase()
    val ext = file.extension.lowercase()
    val folderPath = file.absolutePath.lowercase()
    val allText = "$lower $folderPath ${contentSnippet ?: ""}".lowercase()
    val tokens = MultilingualBridge.tokenize(allText).toSet()

    // Keyword scoring — whole-word for single tokens to avoid "id" matching inside "video"
    for (cat in Category.values()) {
        if (cat == Category.GENERAL) continue
        var score = 0
        for (kw in cat.keywords) {
            if (textContainsKeyword(allText, tokens, kw)) score++
        }
        if (score > 0) scores[cat] = score
    }

    // Extension boosts
    when (ext) {
        "jpg", "jpeg", "png", "heic", "gif", "webp" -> {
            val folder = file.parentFile?.name?.lowercase() ?: ""
            if (folder in listOf("camera", "dcim", "pictures", "whatsapp"))
                scores[Category.PERSONAL] = (scores[Category.PERSONAL] ?: 0) + 3
            else
                scores[Category.MEDIA] = (scores[Category.MEDIA] ?: 0) + 2
        }
        "mp3", "aac", "flac", "wav", "m4a" ->
            scores[Category.MEDIA] = (scores[Category.MEDIA] ?: 0) + 2
        "mp4", "mkv", "avi", "mov" ->
            scores[Category.MEDIA] = (scores[Category.MEDIA] ?: 0) + 2
        "xlsx", "ppt", "pptx", "docx" ->
            scores[Category.WORK] = (scores[Category.WORK] ?: 0) + 2
        "pdf" -> {
            scores[Category.EDUCATION] = (scores[Category.EDUCATION] ?: 0) + 1
            scores[Category.IDENTITY]  = (scores[Category.IDENTITY] ?: 0) + 1
        }
        "py", "js", "ts", "kt", "java", "cpp", "c" ->
            scores[Category.EDUCATION] = (scores[Category.EDUCATION] ?: 0) + 3
    }

    // Folder boosts
    val parentFolder = file.parentFile?.name?.lowercase() ?: ""
    when {
        parentFolder in listOf("music", "audio") ->
            scores[Category.MEDIA] = (scores[Category.MEDIA] ?: 0) + 2
        parentFolder in listOf("pictures", "camera", "dcim", "photos") ->
            scores[Category.PERSONAL] = (scores[Category.PERSONAL] ?: 0) + 2
        parentFolder in listOf("work", "office", "business", "projects", "reports") ->
            scores[Category.WORK] = (scores[Category.WORK] ?: 0) + 3
        parentFolder in listOf("study", "college", "school", "books", "courses") ->
            scores[Category.EDUCATION] = (scores[Category.EDUCATION] ?: 0) + 3
        folderPath.contains("whatsapp") ->
            scores[Category.PERSONAL] = (scores[Category.PERSONAL] ?: 0) + 3
    }

    val result = scores.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
        .take(2)
        .map { it.key }

    return result.ifEmpty { listOf(Category.GENERAL) }
}

// ─────────────────────────────────────────────
// Metadata data class
// ─────────────────────────────────────────────

data class FileMetadata(
    val metadataString: String,
    val categories: List<Category>,
    val contentSnippet: String?,
    val ageBucket: String,
    val sizeBucket: String,
    val typeLabel: String,
    val ownerEntities: List<NameEntity> = emptyList(),
    val ownerConfidence: Float = 0f,
    val pdfMetadata: Map<String, String> = emptyMap(),
    /** Party/person names — searchable via SQL; kept out of embed string. */
    val extractedEntities: List<String> = emptyList(),
    /** Scanned PDF flagged for deferred OCR during fast index pass. */
    val pdfNeedsOcr: Boolean = false
)

// ─────────────────────────────────────────────
// Master metadata builder
// ─────────────────────────────────────────────

fun buildFileMetadata(file: File, ocrText: String? = null, pdfBundle: PdfExtract? = null): FileMetadata {
    val ext        = file.extension.lowercase()
    val typeLabel  = getTypeLabel(ext)
    val sizeBucket = getSizeBucket(file.length())
    val ageBucket  = getAgeBucket(file.lastModified())
    val entities   = extractFilenameEntities(file.nameWithoutExtension)

    val resolvedPdfBundle = pdfBundle ?: if (ext == "pdf") {
        try {
            extractPdfBundle(file, ocrText)
        } catch (e: Exception) {
            Log.w(TAG, "PDF bundle failed for ${file.name}: ${e.message}")
            PdfExtract(null, emptyMap(), null)
        }
    } else null

    val contentSnip = try {
        when {
            resolvedPdfBundle != null -> resolvedPdfBundle.contentSnippet
            else -> extractContent(file)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Content extraction failed for ${file.name}: ${e.message}")
        null
    }

    val categories = classifyCategories(file, contentSnip)

    val pdfMeta = resolvedPdfBundle?.metadata ?: emptyMap()
    val pdfTitle = pdfMeta["title"]?.takeIf { it.isNotBlank() }

    val rawTextForNames = when {
        resolvedPdfBundle?.rawText != null -> resolvedPdfBundle.rawText
        else -> extractRawTextForOwnerNames(file)
    }
    val ownerEntities = extractOwnerNames(file, rawTextForNames, pdfMeta)
    val ownerConfidence = OwnerMatcher.primaryConfidence(ownerEntities)

    val entitySourceText = when {
        resolvedPdfBundle?.entityRawText != null -> resolvedPdfBundle.entityRawText
        resolvedPdfBundle?.rawText != null -> resolvedPdfBundle.rawText
        else -> rawTextForNames
    }
    val extractedEntities = buildExtractedEntities(entitySourceText, ownerEntities)

    val contextualSnippet = contentSnip?.let { snip ->
        stripEntityTokensFromKeywords(snip, extractedEntities).ifBlank { snip }
    }

    val metadataString = if (ext in MEDIA_EXTENSIONS) {
        buildMediaSentence(file, contextualSnippet, categories, ageBucket, entities)
    } else {
        buildDocumentSentence(file, contextualSnippet, categories, ageBucket, entities, pdfTitle)
    }

    Log.d(TAG, "${file.name} → $metadataString (entities=${extractedEntities.take(4)})")

    return FileMetadata(
        metadataString    = metadataString,
        categories        = categories,
        contentSnippet    = contextualSnippet,
        ageBucket         = ageBucket,
        sizeBucket        = sizeBucket,
        typeLabel         = typeLabel,
        ownerEntities     = ownerEntities,
        ownerConfidence   = ownerConfidence,
        pdfMetadata       = pdfMeta,
        extractedEntities = extractedEntities,
        pdfNeedsOcr       = resolvedPdfBundle?.needsOcr == true
    )
}