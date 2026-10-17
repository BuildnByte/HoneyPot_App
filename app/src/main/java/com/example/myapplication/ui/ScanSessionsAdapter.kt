package com.example.myapplication.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.model.SimulationSession
import com.google.android.material.card.MaterialCardView

class ScanSessionsAdapter(
    private val onClick: (SimulationSession) -> Unit
) : ListAdapter<SimulationSession, ScanSessionsAdapter.ScanViewHolder>(ScanSessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScanViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_session, parent, false)
        return ScanViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ScanViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ScanViewHolder(
        itemView: View,
        private val onClick: (SimulationSession) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.tvScanTitle)
        private val subtitleView: TextView = itemView.findViewById(R.id.tvScanSubtitle)
        private val dateView: TextView = itemView.findViewById(R.id.tvScanDate)
        private val iconView: ImageView = itemView.findViewById(R.id.ivSourceIcon)
        private val iconContainer: MaterialCardView = itemView.findViewById(R.id.cardSourceIcon)
        private val riskBadge: TextView = itemView.findViewById(R.id.tvScanRiskBadge)

        fun bind(session: SimulationSession) {
            itemView.setOnClickListener { onClick(session) }

            // Extract info
            val title = UiUtil.formatSessionName(session.name, session.attackerPhone)
            titleView.text = title

            val sourceApp = UiUtil.formatSourceApp(session.serviceCenterAddress.removePrefix("manual:"))
            subtitleView.text = "From $sourceApp"

            dateView.text = DateUtils.getRelativeTimeSpanString(
                session.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            // Setup icon
            iconContainer.setCardBackgroundColor(itemView.context.getColor(R.color.badge_blue_bg))
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(itemView.context.getColor(R.color.accent_blue))

            // Risk badge placeholder - we don't have analysis in SimulationSession directly,
            // but we can query it or just show "Scanned"
            riskBadge.text = "Scanned"
            riskBadge.setBackgroundResource(R.drawable.bg_badge_blue)
            riskBadge.setTextColor(itemView.context.getColor(R.color.accent_blue))
        }
    }
}

class ScanSessionDiffCallback : DiffUtil.ItemCallback<SimulationSession>() {
    override fun areItemsTheSame(oldItem: SimulationSession, newItem: SimulationSession) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: SimulationSession, newItem: SimulationSession) = oldItem == newItem
}
