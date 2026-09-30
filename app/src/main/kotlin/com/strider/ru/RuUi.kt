package com.strider.ru

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object RuUi {

    /** Matches [R.color.ru_focus_ring] alpha — used for type-colored row ripples. */
    private const val EXT_RIPPLE_ALPHA = 0x38

    private val resultDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("d MMM", Locale.getDefault())
    }

    fun wordmarkSpannable(context: Context): SpannableString {
        val text = SpannableString("Ru.")
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.ru_crimson)),
            0, 2,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.ru_amber)),
            2, 3,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    fun applyWordmark(textView: TextView, @DimenRes sizeDimen: Int = R.dimen.home_wordmark_size) {
        textView.text = wordmarkSpannable(textView.context)
        textView.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            textView.resources.getDimension(sizeDimen)
        )
        textView.typeface = fraunces(textView.context)
    }

    fun fraunces(context: Context): Typeface? =
        ResourcesCompat.getFont(context, R.font.fraunces_semibold)

    fun hanken(context: Context, weight: Int = Typeface.NORMAL): Typeface? = when (weight) {
        Typeface.BOLD -> ResourcesCompat.getFont(context, R.font.hanken_grotesk_semibold)
        else -> ResourcesCompat.getFont(context, R.font.hanken_grotesk_regular)
    }

    fun hankenMedium(context: Context): Typeface? =
        ResourcesCompat.getFont(context, R.font.hanken_grotesk_medium)

    fun hankenSemiBold(context: Context): Typeface? =
        ResourcesCompat.getFont(context, R.font.hanken_grotesk_semibold)

    fun tiroDevanagari(context: Context): Typeface? =
        ResourcesCompat.getFont(context, R.font.tiro_devanagari_hindi_regular)

    fun capitalizeCategory(label: String): String =
        label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /** Folder/app label for result rows — WhatsApp, Downloads, Camera, etc. */
    fun sourceLabelFromPath(path: String): String {
        val lower = path.replace('\\', '/').lowercase()
        return when {
            lower.contains("/whatsapp/") -> "WhatsApp"
            lower.contains("/telegram/") -> "Telegram"
            lower.contains("/download") -> "Downloads"
            lower.contains("/gmail") -> "Gmail"
            lower.contains("/dcim/") || lower.contains("/camera/") -> "Camera"
            lower.contains("/documents/") -> "Documents"
            lower.contains("/pictures/") -> "Pictures"
            lower.contains("/drive") -> "Drive"
            else -> {
                path.substringBeforeLast("/", "")
                    .substringAfterLast("/", "")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    .ifBlank { "Files" }
            }
        }
    }

    fun sourceDotColor(context: Context, source: String): Int = when (source) {
        "WhatsApp", "Telegram" -> ContextCompat.getColor(context, R.color.cat_personal)
        "Downloads", "Download", "Documents" -> ContextCompat.getColor(context, R.color.cat_work)
        "Gmail" -> ContextCompat.getColor(context, R.color.cat_identity)
        "Camera", "DCIM", "Pictures" -> ContextCompat.getColor(context, R.color.cat_media)
        "Drive" -> ContextCompat.getColor(context, R.color.ru_amber)
        else -> ContextCompat.getColor(context, R.color.cat_general)
    }

    /** Left accent on search result rows — keyed to ext_* palette (light/dark aware). */
    fun extAccentColor(context: Context, extUpper: String): Int =
        ContextCompat.getColor(context, extAccentColorRes(extUpper))

    fun extAccentColorRes(extUpper: String): Int = when (extUpper) {
        "PDF" -> R.color.ext_pdf
        "DOC", "DOCX" -> R.color.ext_doc
        "XLS", "XLSX", "CSV" -> R.color.ext_xls
        "PPT", "PPTX" -> R.color.ext_ppt
        "JPG", "JPEG", "PNG", "GIF", "WEBP", "HEIC", "BMP", "TIFF", "TIF", "AVIF", "SVG", "RAW", "DNG" -> R.color.ext_img
        "MP3", "WAV", "AAC", "M4A", "FLAC", "OGG", "WMA", "OPUS" -> R.color.ext_audio
        "MP4", "MKV", "AVI", "MOV", "WEBM", "M4V", "3GP" -> R.color.ext_video
        "ZIP", "RAR", "7Z", "TAR", "GZ" -> R.color.ext_zip
        "TXT", "MD", "JSON", "XML", "KT", "JAVA", "PY", "JS", "TS" -> R.color.ext_code
        else -> R.color.ext_default
    }

    fun extAccentRippleColor(context: Context, extUpper: String): Int =
        ColorUtils.setAlphaComponent(extAccentColor(context, extUpper), EXT_RIPPLE_ALPHA)

    /** Press ripple on result rows — tinted to the file-type accent stripe. */
    fun applyResultRowRipple(view: View, extUpper: String) {
        val ctx = view.context
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.resources.getDimension(R.dimen.result_row_radius)
            setColor(Color.WHITE)
        }
        view.foreground = RippleDrawable(
            ColorStateList.valueOf(extAccentRippleColor(ctx, extUpper)),
            null,
            mask,
        )
    }

    fun formatResultDate(lastModified: Long): String {
        if (lastModified <= 0L) return ""
        return resultDateFormat.get().format(Date(lastModified))
    }

    fun formatDisplaySize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun formatResultsCountHeader(context: Context, count: Int): CharSequence {
        val qty = context.resources.getQuantityString(R.plurals.results_files_found, count, count)
        val num = count.toString()
        val start = qty.indexOf(num)
        if (start < 0) return qty
        return SpannableString(qty).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.ru_crimson)),
                start, start + num.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(StyleSpan(Typeface.BOLD), start, start + num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun performTapHaptic(view: View) {
        if (view.isHapticFeedbackEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    fun formatRelativeIndexTime(context: Context, timestampMs: Long): String {
        if (timestampMs <= 0L) return context.getString(R.string.relative_time_just_now)
        val elapsedMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
        return when {
            minutes < 1 -> context.getString(R.string.relative_time_just_now)
            minutes < 60 -> context.getString(R.string.relative_time_minutes, minutes.toInt())
            minutes < 24 * 60 -> context.getString(R.string.relative_time_hours, (minutes / 60).toInt())
            minutes < 48 * 60 -> context.getString(R.string.relative_time_yesterday)
            minutes < 7 * 24 * 60 -> context.getString(
                R.string.relative_time_days,
                (minutes / (24 * 60)).toInt()
            )
            else -> resultDateFormat.get().format(Date(timestampMs))
        }
    }
}
