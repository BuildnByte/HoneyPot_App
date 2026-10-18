package com.honeypot.app.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = OnboardingActivity.PAGES.size

    override fun createFragment(position: Int): Fragment =
        when (position) {
            4 -> OnboardingModelDownloadFragment.newInstance()
            5 -> OnboardingCrossAppSetupFragment.newInstance()
            else -> OnboardingPageFragment.newInstance(position)
        }
}
