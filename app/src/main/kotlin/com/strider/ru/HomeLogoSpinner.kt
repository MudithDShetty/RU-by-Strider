package com.strider.ru

import android.view.Choreographer
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Easter-egg fidget spinner on the idle home wordmark only.
 * Tap to add spin; bearing-style friction slows it until it stops.
 */
class HomeLogoSpinner(private val logo: ImageView) {

    private val choreographer = Choreographer.getInstance()
    private var angularVelocityDegPerSec = 0f
    private var lastFrameNanos = 0L
    private var spinning = false
    private var hardwareLayerActive = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
                return
            }

            val dtSec = ((frameTimeNanos - lastFrameNanos).coerceAtMost(50_000_000L)) / 1_000_000_000f
            lastFrameNanos = frameTimeNanos

            if (abs(angularVelocityDegPerSec) < STOP_VELOCITY_DEG_PER_SEC) {
                stopSpinning()
                return
            }

            logo.rotation += angularVelocityDegPerSec * dtSec
            angularVelocityDegPerSec *= exp(-FRICTION_PER_SEC * dtSec)
            choreographer.postFrameCallback(this)
        }
    }

    fun attach() {
        logo.isClickable = true
        logo.isFocusable = true
        logo.setOnClickListener { view ->
            RuUi.performTapHaptic(view)
            playPressPop(view)
            nudge()
        }
        logo.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
        }
        if (logo.width > 0) {
            logo.pivotX = logo.width / 2f
            logo.pivotY = logo.height / 2f
        }
    }

    fun detach() {
        choreographer.removeFrameCallback(frameCallback)
        stopSpinning(resetRotation = false)
    }

    private fun nudge() {
        val direction = if (angularVelocityDegPerSec == 0f) 1f else angularVelocityDegPerSec.sign
        angularVelocityDegPerSec = (angularVelocityDegPerSec + direction * TAP_IMPULSE_DEG_PER_SEC)
            .coerceIn(-MAX_VELOCITY_DEG_PER_SEC, MAX_VELOCITY_DEG_PER_SEC)

        if (!spinning) {
            spinning = true
            lastFrameNanos = 0L
            if (!hardwareLayerActive) {
                logo.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                hardwareLayerActive = true
            }
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun stopSpinning(resetRotation: Boolean = false) {
        spinning = false
        angularVelocityDegPerSec = 0f
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
        if (hardwareLayerActive) {
            logo.setLayerType(View.LAYER_TYPE_NONE, null)
            hardwareLayerActive = false
        }
        if (resetRotation) {
            logo.rotation = 0f
            logo.scaleX = 1f
            logo.scaleY = 1f
        }
    }

    private fun playPressPop(view: View) {
        view.animate().cancel()
        view.scaleX = PRESS_SCALE
        view.scaleY = PRESS_SCALE
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(PRESS_POP_MS)
            .setInterpolator(OvershootInterpolator(1.35f))
            .start()
    }

    companion object {
        private const val TAP_IMPULSE_DEG_PER_SEC = 2_600f
        private const val MAX_VELOCITY_DEG_PER_SEC = 4_800f
        private const val FRICTION_PER_SEC = 1.72f
        private const val STOP_VELOCITY_DEG_PER_SEC = 5f
        private const val PRESS_SCALE = 0.88f
        private const val PRESS_POP_MS = 200L
    }
}
