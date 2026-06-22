package com.strider.quanto

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** On-device OCR for scanned PDF pages — Latin first, Devanagari fallback. */
object DocumentOcr {

    private const val TAG = "DocumentOcr"
    /** ~108dpi — enough for OCR while keeping bitmap memory low on mid-range phones. */
    private const val RENDER_SCALE = 1.5f
    private const val MAX_BITMAP_DIM = 1_600
    private const val MAX_OCR_CHARS = 4_000

    private val latinRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagariRecognizer =
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    suspend fun ocrPdfPages(file: File): String? = withContext(Dispatchers.IO) {
        if (file.length() == 0L) return@withContext null
        try {
            PDDocument.load(file).use { doc ->
                ocrFromDocument(file, doc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed ${file.name}: ${e.message}")
            null
        }
    }

    /** OCR using an already-open document (avoids double PDF load in the worker). */
    suspend fun ocrFromDocument(file: File, doc: PDDocument): String? {
        if (doc.isEncrypted || doc.numberOfPages == 0) return null
        if (!PdfTextQuality.isOcrCandidate(file, doc.numberOfPages, doc.isEncrypted)) {
            return null
        }

        val renderer = PDFRenderer(doc)
        val sb = StringBuilder()

        val page0 = ocrPage(renderer, 0)
        if (!page0.isNullOrBlank()) sb.append(page0).append(' ')

        if (PdfTextQuality.isWeakOcrText(sb.toString()) && doc.numberOfPages > 1) {
            val page1 = ocrPage(renderer, 1)
            if (!page1.isNullOrBlank()) sb.append(page1)
        }

        return sb.toString().trim().take(MAX_OCR_CHARS).ifBlank { null }
    }

    private suspend fun ocrPage(renderer: PDFRenderer, pageIndex: Int): String? {
        var bitmap: Bitmap? = null
        try {
            bitmap = downscaleIfNeeded(renderer.renderImage(pageIndex, RENDER_SCALE))
            val image = InputImage.fromBitmap(bitmap, 0)
            var text = recognizeLatin(image)
            if (PdfTextQuality.isWeakOcrText(text)) {
                val dev = recognizeDevanagari(image)
                text = mergeText(text, dev)
            }
            return text?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Page $pageIndex OCR failed: ${e.message}")
            return null
        } finally {
            bitmap?.recycle()
        }
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

    private suspend fun recognizeLatin(image: InputImage): String? {
        return try {
            latinRecognizer.process(image).await().text
        } catch (e: Exception) {
            Log.w(TAG, "Latin OCR failed: ${e.message}")
            null
        }
    }

    private suspend fun recognizeDevanagari(image: InputImage): String? {
        return try {
            devanagariRecognizer.process(image).await().text
        } catch (e: Exception) {
            Log.w(TAG, "Devanagari OCR failed: ${e.message}")
            null
        }
    }

    private fun mergeText(primary: String?, secondary: String?): String? {
        val parts = listOfNotNull(primary?.trim(), secondary?.trim()).filter { it.isNotBlank() }
        return parts.joinToString(" ").ifBlank { null }
    }
}
