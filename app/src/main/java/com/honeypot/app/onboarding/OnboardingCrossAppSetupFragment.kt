package com.honeypot.app.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.honeypot.app.R
import com.honeypot.app.manualcapture.HoneypotAccessibilityService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Onboarding slide that prompts users to configure Accessibility Service and
 * "Draw over other apps" (Overlay) permissions for cross-app scanning.
 * Dynamically updates button states when user returns from system Settings.
 */
class OnboardingCrossAppSetupFragment : Fragment() {

    companion object {
        fun newInstance(): OnboardingCrossAppSetupFragment = OnboardingCrossAppSetupFragment()
    }

    private lateinit var cardAccessibility: MaterialCardView
    private lateinit var btnAccessibilityAction: MaterialButton
    private lateinit var tvAccessibilityDesc: TextView

    private lateinit var cardOverlay: MaterialCardView
    private lateinit var btnOverlayAction: MaterialButton
    private lateinit var tvOverlayDesc: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_crossapp_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardAccessibility = view.findViewById(R.id.cardAccessibility)
        btnAccessibilityAction = view.findViewById(R.id.btnAccessibilityAction)
        tvAccessibilityDesc = view.findViewById(R.id.tvAccessibilityDesc)

        cardOverlay = view.findViewById(R.id.cardOverlay)
        btnOverlayAction = view.findViewById(R.id.btnOverlayAction)
        tvOverlayDesc = view.findViewById(R.id.tvOverlayDesc)

        btnAccessibilityAction.setOnClickListener {
            val ctx = requireContext()
            Toast.makeText(ctx, "Select 'Downloaded apps' ➔ 'Honeypot' to turn ON", Toast.LENGTH_LONG).show()
            startActivity(HoneypotAccessibilityService.buildSettingsIntent(ctx))
        }

        btnOverlayAction.setOnClickListener {
            val ctx = requireContext()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivity(fallbackIntent)
            }
        }

        // Staggered entrance animation
        listOf(
            view.findViewById<View>(R.id.cardPreview),
            view.findViewById<View>(R.id.onboardTitle),
            view.findViewById<View>(R.id.onboardBody),
            cardAccessibility,
            cardOverlay
        ).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 50).toLong())
                .setDuration(300)
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        val ctx = context ?: return

        // 1. Accessibility status
        val isAccessibilityActive = HoneypotAccessibilityService.isServiceRunning ||
                HoneypotAccessibilityService.isAccessibilityEnabledInSettings(ctx)

        if (isAccessibilityActive) {
            btnAccessibilityAction.text = "✓ Active"
            btnAccessibilityAction.setBackgroundColor(android.graphics.Color.parseColor("#146C2E"))
            btnAccessibilityAction.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            cardAccessibility.strokeColor = android.graphics.Color.parseColor("#146C2E")
            tvAccessibilityDesc.text = "Service is active and ready for silent on-screen scanning"
        } else {
            btnAccessibilityAction.text = "Enable"
            btnAccessibilityAction.setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            btnAccessibilityAction.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            cardAccessibility.strokeColor = android.graphics.Color.parseColor("#254777")
            tvAccessibilityDesc.text = "Allows silent on-screen capture without screen recording dialogs"
        }

        // 2. Overlay status
        val isOverlayActive = Settings.canDrawOverlays(ctx)

        if (isOverlayActive) {
            btnOverlayAction.text = "✓ Granted"
            btnOverlayAction.setBackgroundColor(android.graphics.Color.parseColor("#146C2E"))
            btnOverlayAction.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            cardOverlay.strokeColor = android.graphics.Color.parseColor("#146C2E")
            tvOverlayDesc.text = "Overlay permission granted — floating bubble ready"
        } else {
            btnOverlayAction.text = "Grant"
            btnOverlayAction.setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            btnOverlayAction.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            cardOverlay.strokeColor = android.graphics.Color.parseColor("#254777")
            tvOverlayDesc.text = "Shows floating scan button and risk breakdown over external apps"
        }
    }
}
