package com.example.myapplication.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.model.ScamEvidence
import com.example.myapplication.model.SimulatedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class TimelineItem {
    abstract val timestamp: Long

    data class Message(val msg: SimulatedMessage) : TimelineItem() {
        override val timestamp: Long = msg.timestamp
    }
    
    data class Evidence(val ev: ScamEvidence, val crossSessionSenders: List<String>) : TimelineItem() {
        override val timestamp: Long = ev.timestamp
    }
}

class TimelineAdapter(
    private val items: List<TimelineItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MESSAGE_A = 0
        private const val TYPE_MESSAGE_V = 1
        private const val TYPE_EVIDENCE = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is TimelineItem.Message -> if (item.msg.senderType == "A") TYPE_MESSAGE_A else TYPE_MESSAGE_V
            is TimelineItem.Evidence -> TYPE_EVIDENCE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MESSAGE_A -> {
                val view = inflater.inflate(R.layout.item_message_a, parent, false)
                MessageViewHolder(view)
            }
            TYPE_MESSAGE_V -> {
                val view = inflater.inflate(R.layout.item_message_v, parent, false)
                MessageViewHolder(view)
            }
            else -> {
                // Reuse the same layout we use for grouped evidence
                val view = inflater.inflate(R.layout.item_evidence_detail, parent, false)
                EvidenceViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TimelineItem.Message -> (holder as MessageViewHolder).bind(item.msg)
            is TimelineItem.Evidence -> (holder as EvidenceViewHolder).bind(item.ev, item.crossSessionSenders)
        }
    }

    override fun getItemCount() = items.size

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textMsg = view.findViewById<TextView>(R.id.messageText)
        private val textTime = view.findViewById<TextView>(R.id.timeText)
        // Ensure brain indicator is hidden since this is just a view log
        private val brainIndicator = view.findViewById<TextView>(R.id.brainIndicator)

        fun bind(msg: SimulatedMessage) {
            textMsg.text = msg.content
            textTime.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.timestamp))
            brainIndicator?.visibility = View.GONE
            
            // disable clicking so it doesn't try to analyze
            itemView.findViewById<View>(R.id.messageCard)?.isClickable = false
        }
    }

    class EvidenceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerRow = view.findViewById<View>(R.id.evidenceTypeHeaderRow)
        private val headerIcon = view.findViewById<android.widget.ImageView>(R.id.evidenceTypeIcon)
        private val headerText = view.findViewById<TextView>(R.id.evidenceTypeHeader)
        private val cardContainer = view.findViewById<View>(R.id.evidenceValue).parent as View
        private val valueText = view.findViewById<TextView>(R.id.evidenceValue)
        private val sourceText = view.findViewById<TextView>(R.id.evidenceSource)
        private val badgeContainer = view.findViewById<View>(R.id.evidenceSeenElsewhere)
        private val badgeText = view.findViewById<TextView>(R.id.evidenceSeenElsewhereText)

        fun bind(evidence: ScamEvidence, crossSessionSenders: List<String>) {
            headerRow.visibility = View.VISIBLE
            
            // Map raw types to clean labels
            val label = when (evidence.entityType) {
                "AMOUNT" -> "Financial Amounts"
                "PERSON" -> "People"
                "ORGANIZATION" -> "Organisations"
                "PHONE" -> "Phone Numbers"
                "UPI_ID" -> "UPI IDs"
                "URL" -> "Links & URLs"
                "ACCOUNT" -> "Bank Accounts"
                "EMAIL" -> "Email Addresses"
                else -> "${evidence.entityType.lowercase().replaceFirstChar { it.uppercase() }}s"
            }
            
            headerText.text = label
            headerText.setTextColor(itemView.context.getColor(R.color.text_secondary))
            
            // Map icons based on entity type
            val iconRes = when (evidence.entityType) {
                "AMOUNT", "ACCOUNT", "UPI_ID" -> R.drawable.ic_message // fallback
                "PERSON", "ORGANIZATION" -> R.drawable.ic_message
                "URL", "EMAIL", "PHONE" -> R.drawable.ic_message
                else -> R.drawable.ic_history
            }
            headerIcon.setImageResource(iconRes)
            headerIcon.imageTintList = android.content.res.ColorStateList.valueOf(itemView.context.getColor(R.color.text_secondary))
            
            cardContainer.visibility = View.VISIBLE
            
            valueText.text = evidence.entityValue
            sourceText.visibility = View.GONE // We already see the source in the timeline
            
            if (crossSessionSenders.isNotEmpty()) {
                badgeContainer.visibility = View.VISIBLE
                val cleanedSenders = crossSessionSenders.map { if (it == "MANUAL_CAPTURE") "Manual Scan" else it }
                badgeText.text = "Also seen in: ${cleanedSenders.joinToString(", ")}"
            } else {
                badgeContainer.visibility = View.GONE
            }
        }
    }
}
