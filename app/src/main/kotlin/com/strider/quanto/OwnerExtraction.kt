package com.strider.quanto

import java.io.File

private val ID_DOC_KEYWORDS = setOf(
    "aadhaar", "aadhar", "adhar", "pan", "passport", "visa", "license", "licence",
    "certificate", "marksheet", "resume", "cv", "biodata", "kyc", "voter", "driving",
    "insurance", "policy", "itr", "form16", "form", "scan", "copy", "card", "id",
    "identity", "document", "doc", "pdf", "jpg", "jpeg", "png", "final", "draft",
    "backup", "new", "old", "front", "back", "page", "img", "photo", "pic"
)

private val PRIMARY_LABEL = Regex(
    """(?i)(?:^|\n|\r)\s*(?:name|full\s*name|नाम)\s*[:\-]?\s*([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)
private val FATHER_LABEL = Regex(
    """(?i)(?:father'?s?\s*name|s/o|son\s+of|पिता)\s*[:\-]?\s*([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)
private val SPOUSE_LABEL = Regex(
    """(?i)(?:spouse|wife|husband|w/o|h/o|पति|पत्नी)\s*[:\-]?\s*([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)
private val GUARDIAN_LABEL = Regex(
    """(?i)(?:c/o|care\s+of|guardian|mother'?s?\s*name|d/o|daughter\s+of)\s*[:\-]?\s*([A-Za-z\u0900-\u097f][A-Za-z\u0900-\u097f\s.'-]{1,48})"""
)

fun extractOwnerNames(
    file: File,
    contentSnippet: String?,
    pdfMetadata: Map<String, String> = emptyMap()
): List<NameEntity> {
    val entities = mutableListOf<NameEntity>()
    entities.addAll(extractNamesFromFilename(file.nameWithoutExtension))
    entities.addAll(extractNamesFromText(contentSnippet ?: ""))

    val author = pdfMetadata["author"]?.trim().orEmpty()
    if (author.isNotBlank() && isNameLike(author)) {
        val authorTokens = UserProfile.tokenizeName(author)
        if (authorTokens.isNotEmpty() && entities.none { it.role == NameRole.PRIMARY }) {
            entities.add(NameEntity(authorTokens, NameRole.PRIMARY, 0.75f))
        }
    }

    return dedupeEntities(entities).take(4)
}

fun extractNamesFromFilename(nameWithoutExt: String): List<NameEntity> {
    val lower = nameWithoutExt.lowercase()
    val result = mutableListOf<NameEntity>()

    when {
        lower.startsWith("resume_") || lower.startsWith("cv_") -> {
            val segment = nameWithoutExt.substringAfter("_").substringBefore("_")
            tokenizePerson(segment)?.let { result.add(NameEntity(it, NameRole.PRIMARY, 0.85f)) }
            return result
        }
    }

    val parts = nameWithoutExt.split(Regex("[_\\-.\\s]+")).filter { it.length >= 2 }
    val nameParts = parts.filter { part ->
        val pl = part.lowercase()
        pl !in ID_DOC_KEYWORDS &&
            !pl.matches(Regex("^\\d+$")) &&
            !pl.matches(Regex("^q[1-4]$", RegexOption.IGNORE_CASE)) &&
            isNameLike(part)
    }

    when (nameParts.size) {
        0 -> { /* none */ }
        1 -> tokenizePerson(nameParts[0])?.let {
            result.add(NameEntity(it, NameRole.PRIMARY, 0.7f))
        }
        else -> {
            val tokens0 = tokenizePerson(nameParts[0])
            val tokens1 = tokenizePerson(nameParts[1])
            if (tokens0 != null && tokens1 != null) {
                result.add(NameEntity(tokens0, NameRole.CO_PRIMARY, 0.5f))
                result.add(NameEntity(tokens1, NameRole.CO_PRIMARY, 0.5f))
            } else {
                nameParts.firstNotNullOfOrNull { tokenizePerson(it) }?.let {
                    result.add(NameEntity(it, NameRole.PRIMARY, 0.65f))
                }
            }
        }
    }

    return result
}

fun extractNamesFromText(text: String): List<NameEntity> {
    if (text.isBlank()) return emptyList()
    val result = mutableListOf<NameEntity>()

    PRIMARY_LABEL.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
        tokenizePerson(raw.trim())?.let {
            result.add(NameEntity(it, NameRole.PRIMARY, 0.9f))
        }
    }
    FATHER_LABEL.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
        tokenizePerson(raw.trim())?.let {
            result.add(NameEntity(it, NameRole.FATHER, 0.5f))
        }
    }
    SPOUSE_LABEL.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
        tokenizePerson(raw.trim())?.let {
            result.add(NameEntity(it, NameRole.SPOUSE, 0.45f))
        }
    }
    GUARDIAN_LABEL.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
        tokenizePerson(raw.trim())?.let {
            result.add(NameEntity(it, NameRole.GUARDIAN, 0.4f))
        }
    }

    return result
}

private fun tokenizePerson(raw: String): List<String>? {
    val tokens = UserProfile.tokenizeName(raw)
    if (tokens.isEmpty()) return null
    if (tokens.all { it in ID_DOC_KEYWORDS }) return null
    return tokens
}

private fun isNameLike(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 2 || trimmed.length > 50) return false
    if (trimmed.matches(Regex("^\\d+$"))) return false
    val letters = trimmed.count { it.isLetter() || it in '\u0900'..'\u097f' }
    return letters >= trimmed.length / 2
}

private fun dedupeEntities(entities: List<NameEntity>): List<NameEntity> {
    val seen = mutableSetOf<String>()
    return entities.filter { e ->
        val key = "${e.role}:${e.tokens.joinToString(",")}"
        if (key in seen) false else {
            seen.add(key)
            true
        }
    }.sortedByDescending { it.confidence }
}
