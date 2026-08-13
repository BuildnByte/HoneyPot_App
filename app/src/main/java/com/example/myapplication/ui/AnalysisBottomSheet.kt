package com.example.myapplication.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.myapplication.R
import com.example.myapplication.model.AiAnalysisLog
import com.example.myapplication.model.EvidenceFlag
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.serialization.json.Json

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

        // New Evidence Section
        val evidenceGroup = view.findViewById<ChipGroup>(R.id.evidenceGroup)
        val evidenceLabel = view.findViewById<View>(R.id.evidenceLabel)
        
        try {
            val flags = Json.decodeFromString<List<EvidenceFlag>>(currentLog.evidenceFlagsJson)
            if (flags.isEmpty()) {
                evidenceLabel.visibility = View.GONE
                evidenceGroup.visibility = View.GONE
            } else {
                evidenceLabel.visibility = View.VISIBLE
                evidenceGroup.visibility = View.VISIBLE
                flags.forEach { flag ->
                    val chip = Chip(context)
                    chip.text = "${flag.indicator}: ${flag.verdict}"
                    
                    val colorRes = when (flag.confidenceLevel.uppercase()) {
                        "HIGH" -> android.R.color.holo_red_light
                        "MEDIUM" -> android.R.color.holo_orange_light
                        else -> android.R.color.darker_gray
                    }
                    chip.setChipBackgroundColorResource(colorRes)
                    chip.setTextColor(android.graphics.Color.WHITE)
                    evidenceGroup.addView(chip)
                }
            }
        } catch (e: Exception) {
            evidenceLabel.visibility = View.GONE
            evidenceGroup.visibility = View.GONE
        }

        // --- Honeypot Reply Suggestion ---
        val replySection = view.findViewById<View>(R.id.replySuggestionSection)
        val replyText   = view.findViewById<TextView>(R.id.suggestedReplyText)
        val copyButton  = view.findViewById<MaterialButton>(R.id.copyReplyButton)

        val reply = currentLog.suggestedReply
        if (currentLog.isScam && reply.isNotBlank()) {
            replySection.visibility = View.VISIBLE
            replyText.text = reply
            copyButton.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Suggested Reply", reply))
                Snackbar.make(view, "Reply copied to clipboard", Snackbar.LENGTH_SHORT).show()
            }
        } else {
            replySection.visibility = View.GONE
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
