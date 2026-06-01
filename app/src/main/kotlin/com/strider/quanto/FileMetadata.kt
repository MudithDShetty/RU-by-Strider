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

fun classifyCategories(file: File, contentSnippet: String?): List<Category> {
    val scores = mutableMapOf<Category, Int>()
    val lower = file.nameWithoutExtension.lowercase()
    val ext = file.extension.lowercase()
    val folderPath = file.absolutePath.lowercase()
    val allText = "$lower $folderPath ${contentSnippet ?: ""}".lowercase()

    // Keyword scoring
    for (cat in Category.values()) {
        if (cat == Category.GENERAL) continue
        var score = 0
        for (kw in cat.keywords) {
            if (allText.contains(kw)) score++
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
    val typeLabel: String
)

// ─────────────────────────────────────────────
// Master metadata builder
// ─────────────────────────────────────────────

fun buildFileMetadata(file: File): FileMetadata {
    val ext        = file.extension.lowercase()
    val nameClean  = file.nameWithoutExtension
        .replace(Regex("[_\\-.]"), " ").trim()
    val typeLabel  = getTypeLabel(ext)
    val sizeBucket = getSizeBucket(file.length())
    val ageBucket  = getAgeBucket(file.lastModified())
    val folder     = file.parentFile?.name ?: ""
    val signals    = extractFilenameSignals(file.nameWithoutExtension)

    // Extract content using ContentExtractor
    val contentSnip = try {
        extractContent(file)
    } catch (e: Exception) {
        Log.w(TAG, "Content extraction failed for ${file.name}: ${e.message}")
        null
    }

    val categories = classifyCategories(file, contentSnip)
    val catLabels  = categories.joinToString(" ") { it.label }

    // Build metadata string
    val parts = mutableListOf<String>()
    parts.add(nameClean)
    parts.add(typeLabel)
    parts.add(catLabels)
    parts.add(sizeBucket)
    parts.add(ageBucket)
    parts.add(folder)
    if (signals.isNotEmpty()) parts.add(signals.joinToString(" "))
    if (!contentSnip.isNullOrBlank()) parts.add(contentSnip)

    val metadataString = parts.filter { it.isNotBlank() }.joinToString(" | ")

    Log.d(TAG, "${file.name} → $metadataString")

    return FileMetadata(
        metadataString = metadataString,
        categories     = categories,
        contentSnippet = contentSnip,
        ageBucket      = ageBucket,
        sizeBucket     = sizeBucket,
        typeLabel      = typeLabel
    )
}