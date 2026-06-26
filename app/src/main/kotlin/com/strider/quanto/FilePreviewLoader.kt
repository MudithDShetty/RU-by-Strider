package com.strider.quanto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
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

    private val PDF_EXTS = setOf("PDF")
    private val IMAGE_EXTS = setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "HEIC")
    private val OFFICE_EXTS = setOf("DOC", "DOCX", "PPT", "PPTX")

    private val OOXML_THUMB_ENTRIES = listOf(
        "docProps/thumbnail.jpeg",
        "docProps/thumbnail.jpg",
        "docProps/thumbnail.png",
    )

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
                    decodeZipEntryBitmap(zip, OOXML_THUMB_ENTRIES.mapNotNull { zip.getEntry(it) })
                        ?: decodeZipEntryBitmap(
                            zip,
                            firstMediaEntries(zip, extUpper),
                        )
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

    private fun decodeZipEntryBitmap(zip: ZipFile, entries: List<ZipEntry>): Bitmap? {
        for (entry in entries) {
            if (entry.isDirectory) continue
            val bitmap = try {
                zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream)?.let(::downscaleIfNeeded)
                }
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun firstMediaEntries(zip: ZipFile, extUpper: String): List<ZipEntry> {
        val prefix = when (extUpper) {
            "PPT", "PPTX" -> "ppt/media/"
            "DOC", "DOCX" -> "word/media/"
            else -> return emptyList()
        }
        return zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(prefix, ignoreCase = true) }
            .filter { entry ->
                val name = entry.name.substringAfterLast('/').lowercase()
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".webp")
            }
            .sortedBy { it.name }
            .toList()
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

    private fun cacheKey(path: String, lastModified: Long): String = "$path|$lastModified"

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
