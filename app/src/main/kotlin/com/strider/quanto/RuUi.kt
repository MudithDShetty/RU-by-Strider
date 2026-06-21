package com.strider.quanto

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

object RuUi {

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
}
