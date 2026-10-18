package com.honeypot.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.honeypot.app.R
import com.honeypot.app.model.SimulationSession

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
        private val iconCard: View = itemView.findViewById(R.id.sessionIconCard)
        private val iconView: android.widget.ImageView = itemView.findViewById(R.id.sessionIconView)
        private val initialView: TextView? = itemView.findViewById(R.id.sessionInitialView)

        fun bind(session: SimulationSession) {
            val rawName = session.attackerPhone.ifBlank { session.name }
            val isManualCapture = session.name.contains("MANUAL_CAPTURE", ignoreCase = true)
                    || session.name.contains("CAPTURE", ignoreCase = true)
                    || session.attackerPhone.isBlank()
            
            val cleanName = when {
                isManualCapture -> {
                    val src = if (session.name.contains("whatsapp", true)) "WhatsApp"
                              else if (session.name.contains("telegram", true)) "Telegram"
                              else if (session.name.contains("instagram", true)) "Instagram"
                              else if (session.name.contains("sms", true)) "SMS"
                              else "Screenshot"
                    "$src Analysis"
                }
                rawName.matches(Regex(".*\\d{10,}.*")) -> {
                    val digits = rawName.filter { it.isDigit() }.takeLast(10)
                    "+91 ${digits.substring(0, 5)} ${digits.substring(5)}"
                }
                else -> rawName.removePrefix("Honeypot - ").removePrefix("[CAPTURE] ").removePrefix("[LIVE] ")
                    .replace("MANUAL_CAPTURE", "Manual Scan")
                    .trim()
            }
            nameView.text = cleanName

            val context = itemView.context
            val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

            if (isManualCapture) {
                iconView.visibility = View.VISIBLE
                initialView?.visibility = View.GONE
                iconView.setImageResource(R.drawable.ic_scan)
                iconCard.setBackgroundResource(if (isDark) R.drawable.bg_m3_avatar_dark else R.drawable.bg_m3_avatar_light)
                iconView.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_blue))
            } else {
                // Sender initial (Google Contacts / Messages paradigm - NO caution emojis)
                iconView.visibility = View.GONE
                initialView?.visibility = View.VISIBLE
                val initialChar = cleanName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"
                initialView?.text = initialChar
                val isScam = !session.isArchived
                if (isScam) {
                    iconCard.setBackgroundResource(if (isDark) R.drawable.bg_m3_avatar_alert_dark else R.drawable.bg_m3_avatar_alert_light)
                    initialView?.setTextColor(context.getColor(R.color.accent_red))
                } else {
                    iconCard.setBackgroundResource(if (isDark) R.drawable.bg_m3_avatar_dark else R.drawable.bg_m3_avatar_light)
                    initialView?.setTextColor(context.getColor(R.color.accent_blue))
                }
            }

            dateView.text = UiUtil.formatDisplayDate(session.createdAt)

            if (session.notes.isNotBlank()) {
                notesView.visibility = View.VISIBLE
                notesView.text = session.notes
            } else {
                notesView.visibility = View.VISIBLE
                notesView.text = if (isManualCapture) "Scanned screenshot evidence" else "Simulated live threat conversation"
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
