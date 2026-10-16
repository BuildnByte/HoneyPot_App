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
        val kbGroup = view.findViewById<android.widget.LinearLayout>(R.id.kbGroup)
        currentLog.kbMatches.split("\n").filter { it.isNotBlank() }.forEach { match ->
            val regex = """(.*)\s*\((\d+\.\d+)\)""".toRegex()
            val result = regex.find(match)
            val name = result?.groupValues?.get(1)?.trim() ?: match
            val pctStr = result?.groupValues?.get(2)
            val pct = pctStr?.let { (it.toFloat() * 100).toInt().toString() + "%" } ?: ""
            
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            val nameView = TextView(context).apply {
                text = name
                setTextColor(requireContext().getColor(R.color.text_secondary))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            val pctBadge = TextView(context).apply {
                text = pct
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(24, 6, 24, 6)
                setTextColor(requireContext().getColor(R.color.accent_blue))
                background = requireContext().getDrawable(R.drawable.bg_badge_blue)
            }
            
            row.addView(nameView)
            if (pct.isNotEmpty()) row.addView(pctBadge)
            kbGroup?.addView(row)
        }
        val timeFormat = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
        view.findViewById<TextView>(R.id.timestampText).text = "Analyzed on: ${timeFormat.format(java.util.Date(currentLog.timestamp))}"

        val scamChip = view.findViewById<Chip>(R.id.scamChip)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.confidenceProgress)
        val confLabel = view.findViewById<TextView>(R.id.confidenceLabel)
        
        if (currentLog.isScam) {
            scamChip.text = "Scam Detected"
            scamChip.chipIcon = requireContext().getDrawable(R.drawable.ic_warning)
            scamChip.chipIconTint = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.attacker_bubble_scam_stroke))
            scamChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.attacker_bubble_scam_bg))
            scamChip.setTextColor(requireContext().getColor(R.color.attacker_bubble_scam_stroke))
            
            progress.setIndicatorColor(requireContext().getColor(R.color.accent_red))
            progress.trackColor = requireContext().getColor(R.color.badge_red_bg)
            confLabel?.setTextColor(requireContext().getColor(R.color.accent_red))
        } else {
            scamChip.text = "Likely Safe"
            scamChip.chipIcon = requireContext().getDrawable(R.drawable.ic_sandbox)
            scamChip.chipIconTint = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.attacker_bubble_safe_stroke))
            scamChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.attacker_bubble_safe_bg))
            scamChip.setTextColor(requireContext().getColor(R.color.attacker_bubble_safe_stroke))
            
            progress.setIndicatorColor(requireContext().getColor(R.color.accent_blue))
            progress.trackColor = requireContext().getColor(R.color.badge_blue_bg)
            confLabel?.setTextColor(requireContext().getColor(R.color.accent_blue))
        }

        val pct = (currentLog.confidence * 100).toInt()
        progress.progress = pct
        confLabel?.text = "$pct%"

        val triggerGroup = view.findViewById<ChipGroup>(R.id.triggerGroup)
        currentLog.firedTriggers.split(",").filter { it.isNotEmpty() }.forEach {
            val chip = Chip(context)
            chip.text = it
            chip.isClickable = false
            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.badge_blue_bg))
            chip.setTextColor(requireContext().getColor(R.color.accent_blue))
            chip.chipStrokeWidth = 1f * requireContext().resources.displayMetrics.density
            chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.accent_blue))
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
                    val label = if (flag.indicator.isNotBlank()) flag.indicator else flag.type.ifBlank { "Evidence" }
                    chip.text = "$label: ${flag.verdict}"
                    chip.isClickable = false
                    val (bgColor, textColor) = when (flag.confidenceLevel.uppercase()) {
                        "HIGH" -> Pair(
                            requireContext().getColor(R.color.attacker_bubble_scam_bg),
                            requireContext().getColor(R.color.attacker_bubble_scam_stroke)
                        )
                        "MEDIUM" -> Pair(
                            requireContext().getColor(R.color.attacker_bubble_warn_bg),
                            requireContext().getColor(R.color.attacker_bubble_warn_stroke)
                        )
                        else -> Pair(
                            requireContext().getColor(android.R.color.darker_gray),
                            requireContext().getColor(android.R.color.white)
                        )
                    }
                    chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bgColor)
                    chip.setTextColor(textColor)
                    evidenceGroup.addView(chip)
                }
            }
        } catch (e: Exception) {
            evidenceLabel.visibility = View.GONE
            evidenceGroup.visibility = View.GONE
        }

        // --- Honeypot Reply Suggestion ---
        val replySection   = view.findViewById<View>(R.id.replySuggestionSection)
        val replyText      = view.findViewById<TextView>(R.id.suggestedReplyText)
        val copyButton     = view.findViewById<MaterialButton>(R.id.copyReplyButton)
        val replySourceBadge = view.findViewById<TextView?>(R.id.replySourceBadge)

        val reply = currentLog.suggestedReply
        if (currentLog.isScam && reply.isNotBlank()) {
            replySection.visibility = View.VISIBLE
            replyText.text = reply

            // Show which engine generated the reply
            replySourceBadge?.apply {
                visibility = View.VISIBLE
                if (currentLog.suggestedReplySource == "cloud") {
                    text = "Generated with Gemini API"
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_brain, 0, 0, 0)
                } else {
                    text = "Generated on-device"
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_sandbox, 0, 0, 0)
                }
                compoundDrawablePadding = 8
            }

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
