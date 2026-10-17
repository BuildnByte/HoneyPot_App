package com.example.myapplication.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.myapplication.R

/** Data class describing one onboarding screen. */
data class OnboardingPage(
    val illustrationRes: Int,       // drawable ID (e.g. real app screenshot)
    val chipLabel: String,          // e.g. "HOME DASHBOARD"
    val title: String,              // bold headline
    val body: String,               // clear feature description
    val isScreenshot: Boolean = true // if true, fits edge-to-edge screenshot; if false, adds padding for vector
)

/** Reusable fragment that renders a single onboarding page. */
class OnboardingPageFragment : Fragment() {

    companion object {
        private const val ARG_PAGE_INDEX = "page_index"

        fun newInstance(index: Int): OnboardingPageFragment {
            return OnboardingPageFragment().apply {
                arguments = Bundle().also { it.putInt(ARG_PAGE_INDEX, index) }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pageIndex = arguments?.getInt(ARG_PAGE_INDEX) ?: 0
        val page = OnboardingActivity.PAGES[pageIndex]

        val illustrationView = view.findViewById<ImageView>(R.id.onboardIllustration)
        illustrationView.setImageResource(page.illustrationRes)
        if (page.isScreenshot) {
            illustrationView.setPadding(0, 0, 0, 0)
            illustrationView.scaleType = ImageView.ScaleType.FIT_CENTER
        } else {
            val pad = (24 * resources.displayMetrics.density).toInt()
            illustrationView.setPadding(pad, pad, pad, pad)
            illustrationView.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        view.findViewById<TextView>(R.id.onboardTitle).text = page.title
        view.findViewById<TextView>(R.id.onboardBody).text = page.body

        // Subtle staggered entrance animation
        listOf(
            view.findViewById<View>(R.id.cardPreview),
            view.findViewById<View>(R.id.onboardTitle),
            view.findViewById<View>(R.id.onboardBody)
        ).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 60).toLong())
                .setDuration(300)
                .start()
        }
    }
}
