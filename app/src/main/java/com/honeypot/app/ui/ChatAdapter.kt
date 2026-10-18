package com.honeypot.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.honeypot.app.R
import com.honeypot.app.model.SimulatedMessage
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(private val onBrainClick: (Int) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<SimulatedMessage>()
    private val analysisMap = mutableMapOf<Int, com.honeypot.app.model.AiAnalysisLog>()
    private val processingIds = mutableSetOf<Int>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val TYPE_V = 0
        private const val TYPE_A = 1
    }

    override fun getItemViewType(position: Int): Int {
        val type = messages[position].senderType
        // Both "V" (victim/simulation) and "U" (user in live honeypot) are outgoing bubbles
        return if (type == "V" || type == "U") TYPE_V else TYPE_A
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_V) {
            VViewHolder(inflater.inflate(R.layout.item_message_v, parent, false))
        } else {
            AViewHolder(inflater.inflate(R.layout.item_message_a, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val timeStr = timeFormat.format(Date(message.timestamp))

        if (holder is VViewHolder) {
            holder.messageText.text = message.content
            holder.timeText.text = timeStr
            if (message.senderType == "U") {
                holder.senderLabel.text = "You (Manual)"
                holder.senderLabel.setTextColor(holder.itemView.context.getColor(R.color.user_bubble_label))
                holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                    holder.itemView.context.getColor(R.color.user_bubble_bg)))
                holder.rightBorder.setBackgroundColor(holder.itemView.context.getColor(R.color.user_bubble_border))
            } else {
                holder.senderLabel.text = "AI Honeypot"
                holder.senderLabel.setTextColor(holder.itemView.context.getColor(R.color.accent_blue))
                holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                    holder.itemView.context.getColor(R.color.bubble_honeypot_bg)))
                holder.rightBorder.setBackgroundColor(holder.itemView.context.getColor(R.color.accent_blue))
            }
        } else if (holder is AViewHolder) {
            holder.messageText.text = message.content
            holder.timeText.text = timeStr
            
            val analysis = analysisMap[message.id]
            val isProcessing = processingIds.contains(message.id)

            when {
                isProcessing -> {
                    holder.brainIndicator.visibility = View.VISIBLE
                    holder.brainIndicator.text = "⏳ AI is thinking…"
                    holder.brainIndicator.setTextColor(android.graphics.Color.GRAY)
                    holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY))
                    holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                        holder.itemView.context.getColor(R.color.attacker_bubble_bg)))
                }
                analysis != null -> {
                    holder.brainIndicator.visibility = View.VISIBLE
                    when {
                        analysis.reason.contains("AI returned invalid format") -> {
                            holder.brainIndicator.text = "⚠️ Analysis Error"
                            holder.brainIndicator.setTextColor(holder.itemView.context.getColor(R.color.attacker_bubble_warn_stroke))
                            holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(
                                holder.itemView.context.getColor(R.color.attacker_bubble_warn_stroke)))
                            holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                                holder.itemView.context.getColor(R.color.attacker_bubble_warn_bg)))
                        }
                        analysis.isScam -> {
                            holder.brainIndicator.text = "SCAM DETECTED"
                            holder.brainIndicator.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
                            holder.brainIndicator.compoundDrawablePadding = 8
                            holder.brainIndicator.setTextColor(holder.itemView.context.getColor(R.color.attacker_bubble_scam_stroke))
                            holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(
                                holder.itemView.context.getColor(R.color.attacker_bubble_scam_stroke)))
                            holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                                holder.itemView.context.getColor(R.color.attacker_bubble_scam_bg)))
                        }
                        else -> {
                            holder.brainIndicator.text = "Safe (AI Analyzed)"
                            holder.brainIndicator.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                            holder.brainIndicator.setTextColor(holder.itemView.context.getColor(R.color.attacker_bubble_safe_stroke))
                            holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(
                                holder.itemView.context.getColor(R.color.attacker_bubble_safe_stroke)))
                            holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                                holder.itemView.context.getColor(R.color.attacker_bubble_safe_bg)))
                        }
                    }
                }
                else -> {
                    val isSessionScam = analysisMap.values.any { it.isScam }
                    val firstAttackerMessageId = messages.firstOrNull { m -> m.senderType == "A" }?.id

                    if (isSessionScam) {
                        holder.brainIndicator.visibility = View.VISIBLE
                        holder.brainIndicator.text = "SCAM DETECTED"
                        holder.brainIndicator.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
                        holder.brainIndicator.compoundDrawablePadding = 8
                        holder.brainIndicator.setTextColor(holder.itemView.context.getColor(R.color.attacker_bubble_scam_stroke))
                        holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(
                            holder.itemView.context.getColor(R.color.attacker_bubble_scam_stroke)))
                        holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                            holder.itemView.context.getColor(R.color.attacker_bubble_scam_bg)))
                    } else if (message.id == firstAttackerMessageId) {
                        holder.brainIndicator.visibility = View.VISIBLE
                        holder.brainIndicator.text = "Tap for AI analysis"
                        holder.brainIndicator.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                        holder.brainIndicator.setTextColor(holder.itemView.context.getColor(R.color.attacker_bubble_label))
                        holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(
                            holder.itemView.context.getColor(R.color.attacker_bubble_label)))
                        holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                            holder.itemView.context.getColor(R.color.attacker_bubble_bg)))
                    } else {
                        holder.brainIndicator.visibility = View.GONE
                        holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY))
                        holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                            holder.itemView.context.getColor(R.color.attacker_bubble_bg)))
                    }
                }
            }
            
            holder.itemView.setOnClickListener {
                onBrainClick(message.id)
            }
        }
    }

    fun setAnalysisResults(logs: List<com.honeypot.app.model.AiAnalysisLog>) {
        analysisMap.clear()
        logs.forEach { analysisMap[it.messageId] = it }
        notifyDataSetChanged()
    }

    fun addAnalysisResult(log: com.honeypot.app.model.AiAnalysisLog) {
        analysisMap[log.messageId] = log
        processingIds.remove(log.messageId)
        val index = messages.indexOfFirst { it.id == log.messageId }
        if (index != -1) notifyItemChanged(index)
    }

    fun setProcessing(messageId: Int, processing: Boolean) {
        if (processing) processingIds.add(messageId)
        else processingIds.remove(messageId)
        val index = messages.indexOfFirst { it.id == messageId }
        if (index != -1) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = messages.size

    fun submitList(newList: List<SimulatedMessage>) {
        messages.clear()
        messages.addAll(newList)
        notifyDataSetChanged()
    }

    fun addMessage(message: SimulatedMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    class VViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderLabel: TextView = view.findViewById(R.id.senderLabel)
        val messageCard: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.messageCard)
        val rightBorder: View = view.findViewById(R.id.rightBorder)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }

    class AViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val brainIndicator: TextView = view.findViewById(R.id.brainIndicator)
        val messageCard: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.messageCard)
    }
}
