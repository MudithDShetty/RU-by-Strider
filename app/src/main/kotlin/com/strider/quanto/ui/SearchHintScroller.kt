package com.strider.quanto.ui

import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView

/** Idle marquee for a clipped search placeholder — one string, no duplication. */
class SearchHintScroller(
    private val host: View,
    private val placeholder: TextView,
    private val editText: EditText,
    private val hintText: CharSequence,
) {
    private var decorEnabled = true
    private var lastHostWidth = 0

    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val w = v.width
        if (w > 0 && w != lastHostWidth && placeholder.visibility == View.VISIBLE) {
            lastHostWidth = w
            scheduleMarqueeUpdate()
        }
    }

    private val globalLayoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            if (host.width <= 0) return
            host.viewTreeObserver.removeOnGlobalLayoutListener(this)
            lastHostWidth = host.width
            sync()
        }
    }

    private val marqueeUpdateRunnable = Runnable { updateMarqueeState() }

    init {
        host.addOnLayoutChangeListener(layoutListener)
        placeholder.isSingleLine = true
        placeholder.text = hintText
        placeholder.setHorizontallyScrolling(true)
        host.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    fun setDecorEnabled(enabled: Boolean) {
        if (decorEnabled == enabled) return
        decorEnabled = enabled
        scheduleMarqueeUpdate()
    }

    fun sync() {
        val showPlaceholder = editText.visibility == View.VISIBLE &&
            editText.text.isNullOrEmpty() &&
            !editText.hasFocus()

        if (!showPlaceholder) {
            stopMarquee()
            placeholder.visibility = View.GONE
            editText.hint = if (editText.text.isNullOrEmpty() && editText.hasFocus()) hintText else ""
            return
        }

        editText.hint = ""
        placeholder.text = hintText
        placeholder.visibility = View.VISIBLE
        placeholder.translationX = 0f
        placeholder.bringToFront()
        syncPlaceholderStyle()
        syncPlaceholderPadding()
        scheduleMarqueeUpdate()
    }

    private fun syncPlaceholderStyle() {
        placeholder.setTextSize(TypedValue.COMPLEX_UNIT_PX, editText.textSize)
        placeholder.typeface = editText.typeface
    }

    private fun syncPlaceholderPadding() {
        placeholder.setPadding(
            editText.compoundPaddingLeft,
            0,
            editText.compoundPaddingRight,
            0,
        )
    }

    private fun scheduleMarqueeUpdate() {
        host.removeCallbacks(marqueeUpdateRunnable)
        host.post(marqueeUpdateRunnable)
    }

    private fun updateMarqueeState() {
        if (placeholder.visibility != View.VISIBLE) return

        syncPlaceholderPadding()
        val available = host.width - placeholder.paddingLeft - placeholder.paddingRight
        if (available <= 0) {
            host.postDelayed(marqueeUpdateRunnable, 48L)
            return
        }

        val textWidth = Layout.getDesiredWidth(hintText, 0, hintText.length, placeholder.paint)
        val shouldMarquee = decorEnabled && textWidth > available + 1f

        if (shouldMarquee) {
            placeholder.ellipsize = TextUtils.TruncateAt.MARQUEE
            placeholder.marqueeRepeatLimit = -1
            restartMarquee()
        } else {
            stopMarquee()
            placeholder.ellipsize = null
        }
    }

    private fun restartMarquee() {
        placeholder.isSelected = false
        placeholder.post {
            if (placeholder.visibility != View.VISIBLE || !decorEnabled) return@post
            placeholder.isSelected = true
        }
    }

    fun stop() {
        stopMarquee()
    }

    fun destroy() {
        stopMarquee()
        host.removeCallbacks(marqueeUpdateRunnable)
        host.removeOnLayoutChangeListener(layoutListener)
        if (host.viewTreeObserver.isAlive) {
            host.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
    }

    private fun stopMarquee() {
        placeholder.isSelected = false
    }
}
