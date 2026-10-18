package com.honeypot.app.onboarding

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * A smooth depth-style page transition where the outgoing page scales down and fades
 * while the incoming page slides in from the right at full size.
 */
class DepthPageTransformer : ViewPager2.PageTransformer {

    private val MIN_SCALE = 0.88f
    private val MIN_ALPHA = 0.5f

    override fun transformPage(page: View, position: Float) {
        when {
            position < -1 -> {
                // Page is way off screen to the left
                page.alpha = 0f
            }
            position <= 0 -> {
                // Page is leaving to the left — scale and fade it
                page.alpha = 1f
                page.translationX = 0f
                page.scaleX = 1f
                page.scaleY = 1f
            }
            position <= 1 -> {
                // Page is entering from the right — apply depth effect
                page.alpha = MAX_ALPHA - position * (MAX_ALPHA - MIN_ALPHA)
                val scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - abs(position))
                page.scaleX = scaleFactor
                page.scaleY = scaleFactor
                // Counteract the default slide so the page appears to "pop in"
                page.translationX = page.width * -position * 0.1f
            }
            else -> {
                page.alpha = 0f
            }
        }
    }

    companion object {
        private const val MAX_ALPHA = 1f
    }
}
