package com.strider.quanto

import java.io.File

/** Heuristics for scanned PDF detection and OCR eligibility (no OCR cost until worker runs). */
object PdfTextQuality {

    private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    private const val MAX_PAGES = 150
    private const val MIN_ALNUM_CHARS = 40
    private const val MIN_WORDS = 8
    private const val MIN_OCR_ALNUM = 30

    private val PHOTO_DIR_MARKERS = listOf("dcim", "camera", "pictures")

    fun isWeakPdfText(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return true
        val alnum = rawText.count { it.isLetterOrDigit() }
        if (alnum < MIN_ALNUM_CHARS) return true
        val words = rawText.split(Regex("\\s+")).filter { it.length > 2 }
        return words.size < MIN_WORDS
    }

    fun isWeakOcrText(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        return text.count { it.isLetterOrDigit() } < MIN_OCR_ALNUM
    }

    fun isOcrCandidate(file: File, pageCount: Int, isEncrypted: Boolean): Boolean {
        if (isEncrypted) return false
        if (file.length() > MAX_FILE_BYTES) return false
        if (pageCount > MAX_PAGES) return false
        return true
    }

    /** Skip OCR for PDFs stored under typical photo folders (DCIM/Camera/Pictures). */
    fun isOcrEligiblePath(file: File): Boolean {
        val path = file.absolutePath.lowercase()
        return PHOTO_DIR_MARKERS.none { marker ->
            path.contains("/$marker/") || path.contains("\\$marker\\")
        }
    }

    fun needsOcr(
        file: File,
        rawText: String?,
        pageCount: Int,
        isEncrypted: Boolean
    ): Boolean {
        return isOcrCandidate(file, pageCount, isEncrypted) &&
            isWeakPdfText(rawText) &&
            isOcrEligiblePath(file)
    }
}
