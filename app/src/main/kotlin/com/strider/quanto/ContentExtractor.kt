package com.strider.quanto

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
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
// Text signal extraction (5A)
// ─────────────────────────────────────────────

data class TextSignals(
    val hasAmounts: Boolean,
    val hasDates: List<String>,
    val hasPhoneNumbers: Boolean,
    val hasEmailAddresses: Boolean,
    val dominantLanguage: String,
    val keyNumbers: List<String>,
    val firstHeading: String?,
    val wordCount: Int
)

fun extractTextSignals(text: String): TextSignals {
    val lower = text.lowercase()
    return TextSignals(
        hasAmounts        = Regex("(rs\\.?|₹|\\$|inr)\\s*[\\d,]+").containsMatchIn(lower),
        hasDates          = Regex("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\b")
                                .findAll(text).map { it.value }.take(3).toList(),
        hasPhoneNumbers   = Regex("\\b[6-9]\\d{9}\\b").containsMatchIn(text),
        hasEmailAddresses = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}").containsMatchIn(text),
        dominantLanguage  = MultilingualBridge.detectDominantLanguage(text) ?: "english",
        keyNumbers        = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").findAll(text).map { it.value }.toList(),
        firstHeading      = text.lines().firstOrNull { it.trim().length in 4..60 && !it.contains(".") }?.trim(),
        wordCount         = text.split(Regex("\\s+")).size
    )
}

fun signalsToString(signals: TextSignals): String {
    val parts = mutableListOf<String>()
    if (signals.hasAmounts)         parts.add("financial amounts")
    if (signals.hasPhoneNumbers)    parts.add("phone number")
    if (signals.hasEmailAddresses)  parts.add("email address")
    when (signals.dominantLanguage) {
        "hindi", "mixed" -> parts.add("${signals.dominantLanguage} language")
        "tamil", "telugu", "bengali", "gujarati", "kannada", "malayalam", "punjabi", "arabic", "cjk" ->
            parts.add("${signals.dominantLanguage} language")
    }
    signals.hasDates.firstOrNull()?.let { parts.add("dated $it") }
    signals.firstHeading?.let { parts.add(it.lowercase()) }
    return parts.joinToString(" ")
}

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
        val keywords = cleanToKeywords(raw)
        val signals  = signalsToString(extractTextSignals(raw))
        listOf(keywords, signals).filter { it.isNotBlank() }.joinToString(" ").take(MAX_CHARS)
            .ifBlank { null }
    } catch (e: Exception) {
        Log.w(TAG, "Text read failed ${file.name}: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────
// PDF extractor + metadata (5C)
// ─────────────────────────────────────────────

fun extractPdfMetadata(file: File): Map<String, String> {
    return try {
        PDDocument.load(file).use { doc ->
            val info = doc.documentInformation
            mapOf(
                "title"    to (info.title    ?: ""),
                "author"   to (info.author   ?: ""),
                "subject"  to (info.subject  ?: ""),
                "keywords" to (info.keywords ?: ""),
                "creator"  to (info.creator  ?: ""),
                "pages"    to doc.numberOfPages.toString()
            ).filter { it.value.isNotBlank() }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

data class PdfExtract(
    val contentSnippet: String?,
    val metadata: Map<String, String>,
    val rawText: String?
)

/** Single PDDocument load — content, metadata, and owner-name text together. */
fun extractPdfBundle(file: File): PdfExtract {
    if (file.length() == 0L) return PdfExtract(null, emptyMap(), null)
    return try {
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) return PdfExtract(null, emptyMap(), null)

            val info = doc.documentInformation
            val metadata = mapOf(
                "title"    to (info.title    ?: ""),
                "author"   to (info.author   ?: ""),
                "subject"  to (info.subject  ?: ""),
                "keywords" to (info.keywords ?: ""),
                "creator"  to (info.creator  ?: ""),
                "pages"    to doc.numberOfPages.toString()
            ).filter { it.value.isNotBlank() }

            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage   = minOf(3, doc.numberOfPages)
            }
            val rawText = stripper.getText(doc).take(MAX_CHARS)
            val metaParts = listOfNotNull(
                metadata["title"]?.let { "title $it" },
                metadata["author"]?.let { "author $it" },
                metadata["keywords"]
            )
            val contentSnippet = (cleanToKeywords(rawText) + " " + metaParts.joinToString(" "))
                .trim()
                .ifBlank { null }

            PdfExtract(contentSnippet, metadata, rawText.ifBlank { null })
        }
    } catch (e: Exception) {
        Log.w(TAG, "PDF extract failed ${file.name}: ${e.message}")
        PdfExtract(null, emptyMap(), null)
    }
}

fun extractPdfContent(file: File): String? = extractPdfBundle(file).contentSnippet

// ─────────────────────────────────────────────
// DOCX extractor
// ─────────────────────────────────────────────

fun extractDocxContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return null
            val xml = zip.getInputStream(entry).bufferedReader().readText()
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
// XLSX extractor
// ─────────────────────────────────────────────

fun extractXlsxContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        ZipFile(file).use { zip ->
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
// PPTX extractor + slide titles (5E)
// ─────────────────────────────────────────────

fun extractPptxTitles(file: File): List<String> {
    return try {
        ZipFile(file).use { zip ->
            zip.entries().toList()
                .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .take(10)
                .mapNotNull { entry ->
                    val xml = zip.getInputStream(entry).bufferedReader().readText()
                    val titleMatch = Regex(
                        "<p:sp>.*?<p:ph type=\"title\".*?</p:sp>",
                        RegexOption.DOT_MATCHES_ALL
                    ).find(xml)
                    titleMatch?.value
                        ?.replace(Regex("<[^>]+>"), " ")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun extractPptxContent(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        ZipFile(file).use { zip ->
            val sb = StringBuilder()
            val titles = extractPptxTitles(file)
            sb.append(titles.joinToString(" ")).append(" ")

            val entries = zip.entries().toList()
                .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .take(5)

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
// EXIF extractor for images (5B enhanced)
// ─────────────────────────────────────────────

data class ExifData(
    val dateTaken: String?,
    val location: String?,
    val make: String?,
    val model: String?,
    val isPortrait: Boolean,
    val isWhatsApp: Boolean,
    val aspectLabel: String?
)

fun extractExifData(file: File): ExifData? {
    return try {
        val exif = ExifInterface(file.absolutePath)

        val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?.replace(":", "-")
            ?.take(10)

        val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        val location = if (lat != null && lon != null) "geotagged" else null

        val make     = exif.getAttribute(ExifInterface.TAG_MAKE)
        val model    = exif.getAttribute(ExifInterface.TAG_MODEL)
        val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)
        val width    = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
        val height   = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)

        val w = width?.toIntOrNull() ?: 0
        val h = height?.toIntOrNull() ?: 0
        val isPortrait = w > 0 && h > 0 && w < h
        val isWhatsApp = software?.lowercase()?.contains("whatsapp") == true
        val aspectLabel = when {
            isPortrait -> "portrait photo"
            w > 0 && h > 0 && w > h -> "landscape photo"
            else -> null
        }

        ExifData(dateTaken, location, make, model, isPortrait, isWhatsApp, aspectLabel)
    } catch (e: Exception) {
        Log.w(TAG, "EXIF failed ${file.name}: ${e.message}")
        null
    }
}

fun exifToString(exif: ExifData): String {
    val parts = mutableListOf<String>()
    exif.dateTaken?.let { parts.add("taken $it") }
    exif.location?.let { parts.add(it) }
    if (exif.isWhatsApp) parts.add("whatsapp image")
    exif.aspectLabel?.let { parts.add(it) }
    exif.make?.let     { parts.add(it.lowercase()) }
    exif.model?.let    { parts.add(it.lowercase()) }
    return parts.joinToString(" ")
}

// ─────────────────────────────────────────────
// ID3 audio tag extractor + header signals (5F)
// ─────────────────────────────────────────────

data class AudioTagData(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: String?
)

fun extractAudioHeader(file: File): Map<String, String> {
    return try {
        val audioFile = AudioFileIO.read(file)
        val header = audioFile.audioHeader
        mapOf(
            "duration" to when {
                header.trackLength < 60  -> "short clip"
                header.trackLength < 300 -> "short song"
                header.trackLength < 600 -> "song"
                else                     -> "long audio"
            },
            "bitrate" to if (header.bitRateAsNumber > 256) "high quality" else "standard"
        )
    } catch (e: Exception) {
        emptyMap()
    }
}

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

fun audioTagsToString(tags: AudioTagData, header: Map<String, String> = emptyMap()): String {
    val parts = mutableListOf<String>()
    tags.genre?.let  { parts.add(it) }
    tags.artist?.let { parts.add(it) }
    tags.title?.let  { parts.add(it) }
    tags.album?.let  { parts.add(it) }
    tags.year?.let   { parts.add(it) }
    header["duration"]?.let { parts.add(it) }
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
            val tags   = extractAudioTags(file)
            val header = extractAudioHeader(file)
            if (tags != null) audioTagsToString(tags, header) else null
        }
        else              -> null
    }
}

/** Raw text for owner-name label extraction (Name:, Father's Name:, etc.). */
fun extractRawTextForOwnerNames(file: File): String? {
    if (file.length() == 0L) return null
    return try {
        when (file.extension.lowercase()) {
            "txt", "md", "csv", "json", "html", "htm",
            "py", "js", "ts", "kt", "java", "cpp", "c", "h" ->
                file.bufferedReader(Charsets.UTF_8).use { it.readText().take(MAX_CHARS) }
            "pdf" -> PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) return null
                PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(3, doc.numberOfPages)
                }.getText(doc).take(MAX_CHARS)
            }
            "docx" -> ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return null
                zip.getInputStream(entry).bufferedReader().readText()
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .take(MAX_CHARS)
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Raw text for owner names failed ${file.name}: ${e.message}")
        null
    }
}
