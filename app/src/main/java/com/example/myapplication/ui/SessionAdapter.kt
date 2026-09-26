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
        private val attackerView: TextView = itemView.findViewById(R.id.sessionAttacker)
        private val notesView: TextView = itemView.findViewById(R.id.sessionNotes)

        fun bind(session: SimulationSession) {
            nameView.text = session.name
            
            val sender = if (session.attackerSenderId.isNotBlank()) {
                "${session.attackerSenderId} (${session.attackerPhone})"
            } else {
                session.attackerPhone
            }
            attackerView.text = "Sender: $sender"

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
