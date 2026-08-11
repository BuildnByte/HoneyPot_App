package com.example.myapplication.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.model.ScamEvidence
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.*

class EvidenceAdapter(private val onShareSession: (String) -> Unit) : 
    ListAdapter<EvidenceAdapter.EvidenceDisplayItem, RecyclerView.ViewHolder>(DiffCallback()) {

    sealed class EvidenceDisplayItem {
        data class Header(val sessionId: String) : EvidenceDisplayItem()
        data class Group(val evidenceGroup: EvidenceGroup) : EvidenceDisplayItem()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_GROUP = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is EvidenceDisplayItem.Header -> TYPE_HEADER
            is EvidenceDisplayItem.Group -> TYPE_GROUP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_evidence_header, parent, false))
        } else {
            GroupViewHolder(inflater.inflate(R.layout.item_evidence_group, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is HeaderViewHolder && item is EvidenceDisplayItem.Header) {
            holder.bind(item.sessionId, onShareSession)
        } else if (holder is GroupViewHolder && item is EvidenceDisplayItem.Group) {
            holder.bind(item.evidenceGroup)
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerTitle: TextView = view.findViewById(R.id.headerTitle)
        private val shareButton: MaterialButton = view.findViewById(R.id.shareSessionButton)

        fun bind(sessionId: String, onShare: (String) -> Unit) {
            headerTitle.text = "Session: ${sessionId.replace("_", " ")}"
            shareButton.setOnClickListener { onShare(sessionId) }
        }
    }

    class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sourceSenderText: TextView = view.findViewById(R.id.sourceSenderText)
        private val timestampText: TextView = view.findViewById(R.id.timestampText)
        private val sourceMessageText: TextView = view.findViewById(R.id.sourceMessageText)
        private val chipGroup: com.google.android.material.chip.ChipGroup = view.findViewById(R.id.chipGroup)
        private val copyAllButton: MaterialButton = view.findViewById(R.id.copyAllButton)
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(group: EvidenceGroup) {
            sourceSenderText.text = group.sourceSender
            timestampText.text = dateFormat.format(Date(group.timestamp))
            sourceMessageText.text = group.sourceMessageText

            sourceMessageText.setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(itemView.context)
                    .setTitle("Original Message")
                    .setMessage(group.sourceMessageText)
                    .setPositiveButton("Close", null)
                    .show()
            }

            chipGroup.removeAllViews()
            group.items.forEach { evidence ->
                val chip = com.google.android.material.chip.Chip(itemView.context)
                chip.text = "${evidence.entityType}: ${evidence.entityValue}"
                chip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                
                val color = when (evidence.entityType) {
                    "UPI_ID" -> "#E91E63" // Pink
                    "PHONE" -> "#2196F3" // Blue
                    "ACCOUNT" -> "#FF9800" // Orange
                    "URL" -> "#9C27B0" // Purple
                    "AMOUNT" -> "#4CAF50" // Green
                    else -> "#757575" // Grey
                }
                chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(color))
                chip.setTextColor(Color.WHITE)
                
                chip.setOnClickListener {
                    copyToClipboard(evidence.entityValue)
                }
                chipGroup.addView(chip)
            }

            copyAllButton.setOnClickListener {
                val allValues = group.items.joinToString("\n") { it.entityValue }
                copyToClipboard(allValues)
            }
        }

        private fun copyToClipboard(text: String) {
            val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Scam Evidence", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(itemView.context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<EvidenceDisplayItem>() {
        override fun areItemsTheSame(oldItem: EvidenceDisplayItem, newItem: EvidenceDisplayItem): Boolean {
            return if (oldItem is EvidenceDisplayItem.Header && newItem is EvidenceDisplayItem.Header) {
                oldItem.sessionId == newItem.sessionId
            } else if (oldItem is EvidenceDisplayItem.Group && newItem is EvidenceDisplayItem.Group) {
                oldItem.evidenceGroup.sourceMessageText == newItem.evidenceGroup.sourceMessageText &&
                oldItem.evidenceGroup.timestamp == newItem.evidenceGroup.timestamp
            } else false
        }

        override fun areContentsTheSame(oldItem: EvidenceDisplayItem, newItem: EvidenceDisplayItem): Boolean =
            oldItem == newItem
    }
}
