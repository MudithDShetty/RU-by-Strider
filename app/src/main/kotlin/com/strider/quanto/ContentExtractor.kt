package com.strider.quanto

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private const val TAG = "ContentExtractor"
private const val MAX_CHARS = 800

// ─────────────────────────────────────────────
// Stopwords
// ─────────────────────────────────────────────

private val STOPWORDS = setOf(
    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
    "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "this", "that", "these", "those", "it", "its",
    "as", "if", "then", "than", "so", "not", "no", "can", "into", "about",
    "also", "just", "more", "some", "such", "only", "other", "than", "when",
    "there", "their", "they", "what", "which", "who", "how", "all", "each"
)

// ─────────────────────────────────────────────
// Keyword cleaner shared util
// ─────────────────────────────────────────────

fun cleanToKeywords(raw: String, maxWords: Int = 60): String {
    return raw.lowercase()
        .replace(Regex("[^a-z0-9\\u0900-\\u097f\\u0600-\\u06ff\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 2 && it !in STOPWORDS }
        .distinct()
        .take(maxWords)
        .joinToString(" ")
}

// ─────────────────────────────────────────────
// Plain text extractor
// ─────────────────────────────────────────────

fun extractTextContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        val raw = file.bufferedReader(Charsets.UTF_8).use {
            it.readText().take(MAX_CHARS)
        }
        cleanToKeywords(raw)
    } catch (e: Exception) {
        Log.w(TAG, "Text read failed ${file.name}: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────
// PDF extractor (PdfBox-Android)
// ─────────────────────────────────────────────

fun extractPdfContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) return null
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage   = minOf(3, doc.numberOfPages) // first 3 pages only
            }
            val text = stripper.getText(doc).take(MAX_CHARS)
            cleanToKeywords(text)
        }
    } catch (e: Exception) {
        Log.w(TAG, "PDF extract failed ${file.name}: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────
// DOCX extractor (ZIP + XML, no extra library)
// ─────────────────────────────────────────────

fun extractDocxContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return null
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            // Strip XML tags, keep text
            val text = xml
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .take(MAX_CHARS)
            cleanToKeywords(text)
        }
    } catch (e: Exception) {
        Log.w(TAG, "DOCX extract failed ${file.name}: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────
// XLSX extractor (ZIP + XML, no extra library)
// ─────────────────────────────────────────────

fun extractXlsxContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        ZipFile(file).use { zip ->
            // Extract shared strings (actual cell text in xlsx)
            val ssEntry = zip.getEntry("xl/sharedStrings.xml") ?: return null
            val xml = zip.getInputStream(ssEntry).bufferedReader().readText()
            val text = xml
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .take(MAX_CHARS)
            cleanToKeywords(text)
        }
    } catch (e: Exception) {
        Log.w(TAG, "XLSX extract failed ${file.name}: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────
// PPTX extractor (ZIP + XML, no extra library)
// ─────────────────────────────────────────────

fun extractPptxContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        ZipFile(file).use { zip ->
            val sb = StringBuilder()
            // pptx slides are at ppt/slides/slide1.xml, slide2.xml etc
            val entries = zip.entries().toList()
                .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .take(5) // first 5 slides

            for (entry in entries) {
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                val text = xml.replace(Regex("<[^>]+>"), " ")
                sb.append(text).append(" ")
                if (sb.length > MAX_CHARS) break
            }
            cleanToKeywords(sb.toString().take(MAX_CHARS))
        }
    } catch (e: Exception) {
        Log.w(TAG, "PPTX extract failed ${file.name}: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────
// EXIF extractor for images
// ─────────────────────────────────────────────

data class ExifData(
    val dateTaken: String?,
    val location: String?,
    val make: String?,
    val model: String?
)

fun extractExifData(file: File): ExifData? {
    return try {
        val exif = ExifInterface(file.absolutePath)

        val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?.replace(":", "-")
            ?.take(10) // "2025-12-15"

        val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        val location = if (lat != null && lon != null) "geotagged" else null

        val make  = exif.getAttribute(ExifInterface.TAG_MAKE)
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)

        ExifData(dateTaken, location, make, model)
    } catch (e: Exception) {
        Log.w(TAG, "EXIF failed ${file.name}: ${e.message}")
        null
    }
}

fun exifToString(exif: ExifData): String {
    val parts = mutableListOf<String>()
    exif.dateTaken?.let { parts.add("taken:$it") }
    exif.location?.let { parts.add(it) }
    exif.make?.let     { parts.add(it.lowercase()) }
    exif.model?.let    { parts.add(it.lowercase()) }
    return parts.joinToString(" ")
}

// ─────────────────────────────────────────────
// ID3 audio tag extractor
// ─────────────────────────────────────────────

data class AudioTagData(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: String?
)

fun extractAudioTags(file: File): AudioTagData? {
    return try {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tag ?: return null
        AudioTagData(
            title  = tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() },
            artist = tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() },
            album  = tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() },
            genre  = tag.getFirst(FieldKey.GENRE).takeIf { it.isNotBlank() },
            year   = tag.getFirst(FieldKey.YEAR).takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        Log.w(TAG, "Audio tags failed ${file.name}: ${e.message}")
        null
    }
}

fun audioTagsToString(tags: AudioTagData): String {
    val parts = mutableListOf<String>()
    tags.title?.let  { parts.add(it) }
    tags.artist?.let { parts.add(it) }
    tags.album?.let  { parts.add(it) }
    tags.genre?.let  { parts.add(it) }
    tags.year?.let   { parts.add(it) }
    return parts.joinToString(" ").lowercase()
}

// ─────────────────────────────────────────────
// Master extractor — routes by extension
// ─────────────────────────────────────────────

fun extractContent(file: File): String? {
    return when (file.extension.lowercase()) {
        "txt", "md"       -> extractTextContent(file)
        "csv"             -> extractTextContent(file)
        "json"            -> extractTextContent(file)
        "html", "htm"     -> extractTextContent(file)?.let {
            // Strip any remaining HTML tags
            it.replace(Regex("<[^>]+>"), " ")
        }
        "py", "js", "ts",
        "kt", "java",
        "cpp", "c", "h"  -> extractTextContent(file)
        "pdf"             -> extractPdfContent(file)
        "docx"            -> extractDocxContent(file)
        "xlsx"            -> extractXlsxContent(file)
        "pptx"            -> extractPptxContent(file)
        "jpg", "jpeg",
        "png", "heic",
        "webp"            -> {
            val exif = extractExifData(file)
            if (exif != null) exifToString(exif) else null
        }
        "mp3", "aac",
        "flac", "m4a",
        "wav"             -> {
            val tags = extractAudioTags(file)
            if (tags != null) audioTagsToString(tags) else null
        }
        else              -> null
    }
}