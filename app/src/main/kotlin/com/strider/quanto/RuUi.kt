package com.strider.quanto

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuUi {

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
}
