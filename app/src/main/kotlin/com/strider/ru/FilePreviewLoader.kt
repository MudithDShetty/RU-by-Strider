package com.strider.ru

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Async first-page / image previews for search result rows — off the search path. */
object FilePreviewLoader {

    private const val MAX_BITMAP_DIM = 180
    private const val PDF_RENDER_SCALE = 1.0f
    private const val DISK_JPEG_QUALITY = 80
    private const val MEMORY_CACHE_MAX_BYTES = 2 * 1024 * 1024
    /** Bump when preview extraction logic changes (invalidates stale disk cache). */
    private const val CACHE_LOADER_VERSION = 5
    private const val DOCX_PREVIEW_TEXT_CHARS = 1200
    private const val DOC_PAGE_BODY_LINES = 7
    private const val PPT_PAGE_BODY_LINES = 4
    private const val COMPOSITE_BODY_LINES = 2
    /** Below this, embedded images are treated as logos/icons — text preview wins. */
    private const val PREVIEW_IMAGE_MIN_DIM = 56
    private const val PREVIEW_IMAGE_MIN_AREA = 56 * 56

    private val PDF_EXTS = setOf("PDF")
    private val IMAGE_EXTS = setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "HEIC")
    private val OFFICE_EXTS = setOf("DOC", "DOCX", "PPT", "PPTX")

    private val DOCX_EXTS = setOf("DOC", "DOCX")
    private val PPTX_EXTS = setOf("PPT", "PPTX")

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private val renderSemaphore = Semaphore(2)
    private val requestCounter = AtomicLong(0)

    private lateinit var memoryCache: LruCache<String, Bitmap>
    private lateinit var diskCacheDir: File

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_MAX_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        diskCacheDir = File(appContext.cacheDir, "thumbnails").also { it.mkdirs() }
    }

    fun supportsPreview(extUpper: String): Boolean =
        extUpper in PDF_EXTS || extUpper in IMAGE_EXTS || extUpper in OFFICE_EXTS

    fun applyThumbClip(thumb: View) {
        val radius = thumb.resources.getDimension(R.dimen.result_thumb_radius)
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        thumb.clipToOutline = true
    }

    fun clearPreview(imageView: ImageView, fallbackView: TextView) {
        imageView.setTag(R.id.tag_preview_request, null)
        imageView.setImageDrawable(null)
        fallbackView.visibility = View.GONE
    }

    fun loadPreview(
        imageView: ImageView,
        fallbackView: TextView,
        file: File,
        extUpper: String,
        lastModified: Long,
    ) {
        if (!::appContext.isInitialized) return
        if (!supportsPreview(extUpper)) return

        val cacheKey = cacheKey(file.absolutePath, lastModified)
        val requestId = requestCounter.incrementAndGet()
        imageView.setTag(R.id.tag_preview_request, requestId)

        memoryCache.get(cacheKey)?.let { cached ->
            if (imageView.getTag(R.id.tag_preview_request) == requestId) {
                imageView.setImageBitmap(cached)
                fallbackView.visibility = View.GONE
            }
            return
        }

        imageView.setImageDrawable(null)
        fallbackView.visibility = View.GONE

        scope.launch {
            val bitmap = loadBitmap(file, extUpper, cacheKey)
            withContext(Dispatchers.Main) {
                if (imageView.getTag(R.id.tag_preview_request) != requestId) return@withContext
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    fallbackView.visibility = View.GONE
                } else {
                    imageView.setImageDrawable(null)
                    showCornerFallback(fallbackView, extUpper)
                }
            }
        }
    }

    private suspend fun loadBitmap(file: File, extUpper: String, cacheKey: String): Bitmap? {
        readDiskCache(cacheKey)?.let { bitmap ->
            memoryCache.put(cacheKey, bitmap)
            return bitmap
        }

        val bitmap = when {
            extUpper in PDF_EXTS -> renderPdfPreview(file)
            extUpper in IMAGE_EXTS -> decodeImagePreview(file)
            extUpper in OFFICE_EXTS -> extractOfficePreview(file, extUpper)
            else -> null
        } ?: return null

        memoryCache.put(cacheKey, bitmap)
        writeDiskCache(cacheKey, bitmap)
        return bitmap
    }

    private suspend fun renderPdfPreview(file: File): Bitmap? = renderSemaphore.withPermit {
        if (!file.isFile || file.length() == 0L) return@withPermit null
        try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted || doc.numberOfPages == 0) return@withPermit null
                val renderer = PDFRenderer(doc)
                val rendered = renderer.renderImage(0, PDF_RENDER_SCALE)
                downscaleIfNeeded(rendered)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractOfficePreview(file: File, extUpper: String): Bitmap? =
        renderSemaphore.withPermit {
            if (!file.isFile || file.length() == 0L) return@withPermit null
            if (!isZipArchive(file)) return@withPermit null
            try {
                ZipFile(file).use { zip ->
                    decodeZipEntryBitmap(zip, ooxmlThumbEntries(zip))
                        ?: when (extUpper) {
                            in DOCX_EXTS -> renderOoxmlPreview(
                                zip,
                                presentation = false,
                                mediaEntries = { docxMediaEntries(zip) },
                                textExtractor = { extractDocxFirstPageText(zip) },
                            )
                            in PPTX_EXTS -> renderOoxmlPreview(
                                zip,
                                presentation = true,
                                mediaEntries = { pptxSlide1MediaEntries(zip) },
                                fallbackMediaEntries = { pptxMediaEntries(zip) },
                                textExtractor = { extractPptxSlide1Text(zip) },
                            )
                            else -> null
                        }
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun isZipArchive(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(2)
                input.read(header) == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeZipEntryBitmap(zip: ZipFile, entries: List<ZipEntry>): Bitmap? =
        decodeBestMediaBitmap(zip, entries)

    /** Prefer the largest embedded image — usually the hero figure, not a corner logo. */
    private fun decodeBestMediaBitmap(zip: ZipFile, entries: List<ZipEntry>): Bitmap? {
        var best: Bitmap? = null
        var bestArea = 0
        for (entry in entries) {
            if (entry.isDirectory) continue
            val bitmap = try {
                zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream)?.let(::downscaleIfNeeded)
                }
            } catch (_: Exception) {
                null
            } ?: continue
            val area = bitmap.width * bitmap.height
            if (area > bestArea) {
                best?.recycle()
                best = bitmap
                bestArea = area
            } else {
                bitmap.recycle()
            }
        }
        return best
    }

    private fun renderOoxmlPreview(
        zip: ZipFile,
        presentation: Boolean,
        mediaEntries: () -> List<ZipEntry>,
        fallbackMediaEntries: () -> List<ZipEntry> = { emptyList() },
        textExtractor: () -> String?,
    ): Bitmap? {
        val text = textExtractor()?.takeIf { it.isNotBlank() }
        val image = decodeBestMediaBitmap(zip, mediaEntries())
            ?: decodeBestMediaBitmap(zip, fallbackMediaEntries())

        if (image == null && text == null) return null
        if (image == null) return renderDocumentPagePreview(text!!, presentation)

        val prominent = isProminentPreviewImage(image)
        val meaningfulText = hasMeaningfulPreviewText(text)

        return when {
            prominent && meaningfulText ->
                renderCompositePagePreview(image, text!!, presentation)
            prominent || !meaningfulText ->
                frameOnDocumentPage(image)
            else -> {
                image.recycle()
                renderDocumentPagePreview(text!!, presentation)
            }
        }
    }

    private fun isProminentPreviewImage(bitmap: Bitmap): Boolean {
        val minDim = minOf(bitmap.width, bitmap.height)
        val area = bitmap.width * bitmap.height
        return minDim >= PREVIEW_IMAGE_MIN_DIM && area >= PREVIEW_IMAGE_MIN_AREA
    }

    private fun hasMeaningfulPreviewText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val chars = lines.sumOf { it.length }
        return chars >= 24 || lines.size >= 2
    }

    private fun ooxmlThumbEntries(zip: ZipFile): List<ZipEntry> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("docProps/thumbnail.", ignoreCase = true) }
            .filter { entry ->
                val name = entry.name.lowercase()
                name.endsWith(".jpeg") || name.endsWith(".jpg") || name.endsWith(".png")
            }
            .sortedBy { it.name }
            .toList()

    private fun extractPptxSlide1Text(zip: ZipFile): String? {
        val slideEntry = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.matches(Regex("ppt/slides/slide1\\.xml", RegexOption.IGNORE_CASE)) }
            .firstOrNull()
            ?: return null
        val xml = zip.getInputStream(slideEntry).bufferedReader().readText()
        return ooxmlXmlToPlainText(xml, DOCX_PREVIEW_TEXT_CHARS)
    }

    private fun docxMediaEntries(zip: ZipFile): List<ZipEntry> =
        mediaEntriesFromRels(zip, "word/_rels/document.xml.rels", "word/media/")
            .ifEmpty { allMediaEntries(zip, "word/media/") }

    private fun pptxSlide1MediaEntries(zip: ZipFile): List<ZipEntry> =
        mediaEntriesFromRels(zip, "ppt/slides/_rels/slide1.xml.rels", "ppt/media/")

    private fun pptxMediaEntries(zip: ZipFile): List<ZipEntry> =
        allMediaEntries(zip, "ppt/media/")

    private fun mediaEntriesFromRels(zip: ZipFile, relsPath: String, mediaRoot: String): List<ZipEntry> {
        val entry = zip.getEntry(relsPath) ?: return emptyList()
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        val targets = Regex("""Target="([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(xml)
            .map { match -> normalizeOoxmlMediaPath(match.groupValues[1], mediaRoot) }
            .filter { path -> isImagePath(path) }
            .toList()
        return targets.mapNotNull { zip.getEntry(it) }
    }

    private fun allMediaEntries(zip: ZipFile, mediaRoot: String): List<ZipEntry> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(mediaRoot, ignoreCase = true) }
            .filter { isImagePath(it.name) }
            .sortedBy { it.name }
            .toList()

    private fun normalizeOoxmlMediaPath(target: String, mediaRoot: String): String {
        val fileName = when {
            target.startsWith("../media/", ignoreCase = true) ->
                target.substringAfterLast('/')
            target.startsWith("media/", ignoreCase = true) ->
                target.substringAfterLast('/')
            else -> target.substringAfterLast('/')
        }
        return mediaRoot + fileName
    }

    private fun isImagePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private fun extractOoxmlPlainText(zip: ZipFile, entryName: String, maxChars: Int): String? {
        val entry = zip.getEntry(entryName) ?: return null
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        return ooxmlXmlToPlainText(xml, maxChars)
    }

    private fun extractDocxFirstPageText(zip: ZipFile): String? {
        val entry = zip.getEntry("word/document.xml") ?: return null
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        val firstPageXml = Regex("""<w:br[^>]*w:type="page"[^>]*/>""", RegexOption.IGNORE_CASE)
            .split(xml)
            .firstOrNull()
            ?: xml
        return ooxmlXmlToPlainText(firstPageXml, DOCX_PREVIEW_TEXT_CHARS)
    }

    private fun ooxmlXmlToPlainText(xml: String, maxChars: Int): String =
        xml.replace(Regex("<w:tab[^>]*/>", RegexOption.IGNORE_CASE), "\t")
            .replace(Regex("</w:p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</a:p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("[ \\t\\r\\f\\v]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
            .take(maxChars)
            .trim()

    private fun renderDocumentPagePreview(text: String, presentation: Boolean): Bitmap {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val title = lines.firstOrNull()?.take(if (presentation) 56 else 64).orEmpty()
        val bodyLines = lines.drop(1).take(if (presentation) PPT_PAGE_BODY_LINES else DOC_PAGE_BODY_LINES)
            .map { it.take(88) }

        val width = MAX_BITMAP_DIM
        val height = (MAX_BITMAP_DIM * 1.28f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val padX = 12f
        val padTop = 14f
        val contentWidth = (width - padX * 2).toInt().coerceAtLeast(1)
        var y = padTop

        if (title.isNotBlank()) {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A1530")
                textSize = if (presentation) 12.5f else 11.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val titleLayout = StaticLayout.Builder
                .obtain(title, 0, title.length, titlePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .setMaxLines(2)
                .build()
            canvas.save()
            canvas.translate(padX, y)
            titleLayout.draw(canvas)
            canvas.restore()
            y += titleLayout.height + 8f
        }

        if (bodyLines.isNotEmpty()) {
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3A3548")
                textSize = if (presentation) 9f else 8.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val body = bodyLines.joinToString("\n")
            val bodyLayout = StaticLayout.Builder
                .obtain(body, 0, body.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .setMaxLines(if (presentation) PPT_PAGE_BODY_LINES else DOC_PAGE_BODY_LINES)
                .build()
            canvas.save()
            canvas.translate(padX, y)
            bodyLayout.draw(canvas)
            canvas.restore()
        }

        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.parseColor("#1A000000")
            },
        )
        return bitmap
    }

    /** Image on top + title and a couple of lines — reads like a real first page. */
    private fun renderCompositePagePreview(
        image: Bitmap,
        text: String,
        presentation: Boolean,
    ): Bitmap {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = lines.firstOrNull()?.take(if (presentation) 48 else 56).orEmpty()
        val bodyLines = lines.drop(1).take(COMPOSITE_BODY_LINES).map { it.take(72) }

        val width = MAX_BITMAP_DIM
        val height = (MAX_BITMAP_DIM * 1.28f).toInt()
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val padX = 10f
        val padTop = 10f
        val imageZoneH = height * 0.50f
        val imageMaxW = width - padX * 2
        val imageScale = minOf(imageMaxW / image.width, (imageZoneH - padTop) / image.height)
        val drawW = image.width * imageScale
        val drawH = image.height * imageScale
        val imageLeft = (width - drawW) / 2f
        val imageTop = padTop + ((imageZoneH - padTop - drawH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(
            image,
            null,
            android.graphics.RectF(imageLeft, imageTop, imageLeft + drawW, imageTop + drawH),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        image.recycle()

        val contentWidth = (width - padX * 2).toInt().coerceAtLeast(1)
        var y = imageZoneH + 6f

        if (title.isNotBlank()) {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A1530")
                textSize = if (presentation) 10.5f else 10f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val titleLayout = StaticLayout.Builder
                .obtain(title, 0, title.length, titlePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .setMaxLines(1)
                .build()
            canvas.save()
            canvas.translate(padX, y)
            titleLayout.draw(canvas)
            canvas.restore()
            y += titleLayout.height + 4f
        }

        if (bodyLines.isNotEmpty()) {
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3A3548")
                textSize = 8f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val body = bodyLines.joinToString("\n")
            val bodyLayout = StaticLayout.Builder
                .obtain(body, 0, body.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .setMaxLines(COMPOSITE_BODY_LINES)
                .build()
            canvas.save()
            canvas.translate(padX, y)
            bodyLayout.draw(canvas)
            canvas.restore()
        }

        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.parseColor("#1A000000")
            },
        )
        return page
    }

    /** Letterbox embedded slide/media images on a white page like PDF thumbnails. */
    private fun frameOnDocumentPage(source: Bitmap): Bitmap {
        val width = MAX_BITMAP_DIM
        val height = (MAX_BITMAP_DIM * 1.28f).toInt()
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val pad = 6f
        val maxW = width - pad * 2
        val maxH = height - pad * 2
        val scale = minOf(maxW / source.width, maxH / source.height)
        val drawW = source.width * scale
        val drawH = source.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        val dest = android.graphics.RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(source, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.parseColor("#1A000000")
            },
        )
        if (source !== page) source.recycle()
        return page
    }

    private fun fallbackTextColor(extUpper: String): Int = when {
        extUpper in IMAGE_EXTS -> R.color.text_secondary
        extUpper in OFFICE_EXTS -> R.color.ru_amber
        else -> R.color.ru_crimson
    }

    private fun decodeImagePreview(file: File): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BITMAP_DIM)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.let(::downscaleIfNeeded)
        } catch (_: Exception) {
            null
        }
    }

    private fun readDiskCache(cacheKey: String): Bitmap? {
        val cacheFile = diskCacheFile(cacheKey)
        if (!cacheFile.isFile) return null
        return try {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.let(::downscaleIfNeeded)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskCache(cacheKey: String, bitmap: Bitmap) {
        val cacheFile = diskCacheFile(cacheKey)
        try {
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_JPEG_QUALITY, out)
            }
        } catch (_: Exception) {
            cacheFile.delete()
        }
    }

    private fun diskCacheFile(cacheKey: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskCacheDir, "$hash.jpg")
    }

    private fun cacheKey(path: String, lastModified: Long): String =
        "$CACHE_LOADER_VERSION|$path|$lastModified"

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) {
            sample *= 2
        }
        return sample
    }

    private fun downscaleIfNeeded(source: Bitmap): Bitmap {
        val maxDim = maxOf(source.width, source.height)
        if (maxDim <= MAX_BITMAP_DIM) return source
        val scale = MAX_BITMAP_DIM.toFloat() / maxDim
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun showCornerFallback(fallbackView: TextView, extUpper: String) {
        val ctx = fallbackView.context
        fallbackView.text = extUpper
        fallbackView.textSize = 8f
        fallbackView.setBackgroundResource(R.drawable.bg_result_thumb_fallback_badge)
        fallbackView.setTextColor(ContextCompat.getColor(ctx, fallbackTextColor(extUpper)))
        fallbackView.setPadding(
            dp(ctx, 4),
            dp(ctx, 2),
            dp(ctx, 4),
            dp(ctx, 2),
        )
        val lp = (fallbackView.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        lp.setMargins(0, 0, dp(ctx, 4), dp(ctx, 4))
        fallbackView.layoutParams = lp
        fallbackView.visibility = View.VISIBLE
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
