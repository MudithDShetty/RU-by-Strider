package com.strider.ru

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/** Cached row chrome with a type-colored left accent (matches bg_result_row_top). */
class ResultRowBackground(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val radiusPx = context.resources.getDimension(R.dimen.result_row_radius)
    private val innerLeftRadiusPx = context.resources.getDimension(R.dimen.result_row_inner_left_radius)
    private val accentWidthPx = context.resources.getDimensionPixelSize(R.dimen.result_row_accent_width)
    private val oneDp = (1f * density).toInt().coerceAtLeast(1)
    private val twoDp = (2f * density).toInt()

    private val shadowDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(ContextCompat.getColor(context, R.color.ru_result_row_shadow))
    }

    private val accentDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0, 0),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
    }

    private val faceDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(ContextCompat.getColor(context, R.color.ru_bg))
        cornerRadii = floatArrayOf(
            innerLeftRadiusPx, innerLeftRadiusPx,
            radiusPx, radiusPx,
            innerLeftRadiusPx, innerLeftRadiusPx,
            radiusPx, radiusPx,
        )
    }

    private val borderDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(Color.TRANSPARENT)
        setStroke(oneDp, ContextCompat.getColor(context, R.color.divider))
    }

    private val layers = LayerDrawable(
        arrayOf(shadowDrawable, accentDrawable, faceDrawable, borderDrawable),
    )

    init {
        // Symmetric shadow inset — avoids bottom-right corner bleeding past the border.
        layers.setLayerInset(0, oneDp, oneDp, oneDp, oneDp)
        layers.setLayerInsetLeft(2, accentWidthPx)
    }

    fun setAccentColor(accent: Int) {
        val deep = ColorUtils.blendARGB(accent, Color.BLACK, 0.22f)
        accentDrawable.colors = intArrayOf(deep, accent)
    }

    fun applyTo(view: View) {
        view.background = layers
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }
}
