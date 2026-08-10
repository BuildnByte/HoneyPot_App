package com.example.myapplication.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.myapplication.R
import com.example.myapplication.model.AiAnalysisLog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator

class AnalysisBottomSheet : BottomSheetDialogFragment() {

    private var log: AiAnalysisLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_analysis, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentLog = log ?: return

        view.findViewById<TextView>(R.id.reasoningText).text = currentLog.reason
        view.findViewById<TextView>(R.id.kbText).text = currentLog.kbMatches

        val timeFormat = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
        view.findViewById<TextView>(R.id.timestampText).text = "Analyzed on: ${timeFormat.format(java.util.Date(currentLog.timestamp))}"

        val scamChip = view.findViewById<Chip>(R.id.scamChip)
        if (currentLog.isScam) {
            scamChip.text = "Scam Detected"
            scamChip.setChipBackgroundColorResource(android.R.color.holo_red_light)
        } else {
            scamChip.text = "Likely Safe"
            scamChip.setChipBackgroundColorResource(android.R.color.holo_green_light)
        }

        val progress = view.findViewById<LinearProgressIndicator>(R.id.confidenceProgress)
        progress.progress = (currentLog.confidence * 100).toInt()

        val triggerGroup = view.findViewById<ChipGroup>(R.id.triggerGroup)
        currentLog.firedTriggers.split(",").filter { it.isNotEmpty() }.forEach {
            val chip = Chip(context)
            chip.text = it
            triggerGroup.addView(chip)
        }
    }

    companion object {
        const val TAG = "AnalysisBottomSheet"
        fun newInstance(log: AiAnalysisLog): AnalysisBottomSheet {
            return AnalysisBottomSheet().apply {
                this.log = log
            }
        }
    }
}
