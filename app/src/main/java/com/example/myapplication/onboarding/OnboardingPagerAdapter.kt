package com.example.myapplication.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = OnboardingActivity.PAGES.size

    override fun createFragment(position: Int): Fragment =
        if (position == 4) {
            OnboardingModelDownloadFragment.newInstance()
        } else {
            OnboardingPageFragment.newInstance(position)
        }
}
