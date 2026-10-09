package com.example.myapplication.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.model.SimulationSession

class SessionAdapter(
    private val onClick: (SimulationSession) -> Unit,
    private val onLongClick: (SimulationSession) -> Unit
) : ListAdapter<SimulationSession, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        itemView: View,
        val onClick: (SimulationSession) -> Unit,
        val onLongClick: (SimulationSession) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.sessionName)
        private val dateView: TextView = itemView.findViewById(R.id.sessionDate)
        private val countView: TextView = itemView.findViewById(R.id.sessionAttacker)
        private val scamTypeView: TextView = itemView.findViewById(R.id.sessionScamType)
        private val notesView: TextView = itemView.findViewById(R.id.sessionNotes)
        private val iconCard: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.sessionIconCard)
        private val iconView: android.widget.ImageView = itemView.findViewById(R.id.sessionIconView)

        fun bind(session: SimulationSession) {
            val rawName = session.attackerPhone.ifBlank { session.name }
            val isManualCapture = session.name.contains("MANUAL_CAPTURE", ignoreCase = true)
                    || session.name.contains("CAPTURE", ignoreCase = true)
                    || session.attackerPhone.isBlank()
            
            val cleanName = when {
                isManualCapture -> "Screenshot Analysis"
                rawName.matches(Regex(".*\\d{10,}.*")) -> {
                    val digits = rawName.filter { it.isDigit() }.takeLast(10)
                    val last4 = digits.takeLast(4)
                    "+91 XXXXX $last4"
                }
                else -> rawName.removePrefix("Honeypot - ").removePrefix("[CAPTURE] ").removePrefix("[LIVE] ")
                    .replace("MANUAL_CAPTURE", "Manual Scan")
                    .trim()
            }
            nameView.text = cleanName

            val statusStr = if (session.isArchived) "Archived" else "Active"
            countView.text = statusStr

            // Scam type fallback for simple Session
            val typeStr = if (isManualCapture) "Manual Scan" else "Flagged Threat"
            scamTypeView.text = typeStr

            val context = itemView.context
            when {
                isManualCapture -> {
                    iconView.setImageResource(R.drawable.ic_scan)
                    iconCard.setCardBackgroundColor(context.getColor(R.color.bg_surface))
                    iconView.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_secondary))
                    scamTypeView.setBackgroundResource(R.drawable.bg_badge_blue)
                    scamTypeView.setTextColor(context.getColor(R.color.accent_blue))
                }
                else -> { 
                    iconView.setImageResource(R.drawable.ic_message)
                    iconCard.setCardBackgroundColor(context.getColor(R.color.badge_red_bg))
                    iconView.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_red))
                    scamTypeView.setBackgroundResource(R.drawable.bg_badge_red)
                    scamTypeView.setTextColor(context.getColor(R.color.accent_red))
                }
            }

            dateView.text = DateUtils.getRelativeTimeSpanString(
                session.createdAt,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            if (session.notes.isNotBlank()) {
                notesView.visibility = View.VISIBLE
                notesView.text = session.notes
            } else {
                notesView.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(session) }
            itemView.setOnLongClickListener { 
                onLongClick(session)
                true
            }
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<SimulationSession>() {
        override fun areItemsTheSame(oldItem: SimulationSession, newItem: SimulationSession) = 
            oldItem.id == newItem.id
            
        override fun areContentsTheSame(oldItem: SimulationSession, newItem: SimulationSession) = 
            oldItem == newItem
    }
}
