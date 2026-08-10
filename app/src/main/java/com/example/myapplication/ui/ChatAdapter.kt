package com.example.myapplication.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.model.SimulatedMessage
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(private val onBrainClick: (Int) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<SimulatedMessage>()
    private val analysisMap = mutableMapOf<Int, com.example.myapplication.model.AiAnalysisLog>()
    private val processingIds = mutableSetOf<Int>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val TYPE_V = 0
        private const val TYPE_A = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderType == "V") TYPE_V else TYPE_A
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
        } else if (holder is AViewHolder) {
            holder.messageText.text = message.content
            holder.timeText.text = timeStr
            
            val analysis = analysisMap[message.id]
            val isProcessing = processingIds.contains(message.id)

            when {
                isProcessing -> {
                    holder.brainIndicator.visibility = View.VISIBLE
                    holder.brainIndicator.text = "⏳ AI is thinking..."
                    holder.brainIndicator.setTextColor(android.graphics.Color.GRAY)
                    holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.LTGRAY))
                }
                analysis != null -> {
                    holder.brainIndicator.visibility = View.VISIBLE
                    when {
                        analysis.reason.contains("AI returned invalid format") -> {
                            holder.brainIndicator.text = "⚠️ Analysis Error"
                            holder.brainIndicator.setTextColor(android.graphics.Color.parseColor("#FF9800")) // Orange
                            holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800")))
                            holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFFFF3E0.toInt()))
                        }
                        analysis.isScam -> {
                            holder.brainIndicator.text = "⚠️ SCAM DETECTED"
                            holder.brainIndicator.setTextColor(android.graphics.Color.RED)
                            holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED))
                            holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFFFEBEE.toInt()))
                        }
                        else -> {
                            holder.brainIndicator.text = "✅ Safe (AI Analyzed)"
                            holder.brainIndicator.setTextColor(0xFF4CAF50.toInt())
                            holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt()))
                            holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFE8F5E9.toInt()))
                        }
                    }
                }
                else -> {
                    // Not attacker message or no analysis yet
                    holder.brainIndicator.visibility = View.VISIBLE
                    holder.brainIndicator.text = "🧠 Tap for AI analysis"
                    holder.brainIndicator.setTextColor(0xFF9C27B0.toInt())
                    holder.messageCard.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFCE93D8.toInt()))
                    holder.messageCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFF3E5F5.toInt()))
                }
            }
            
            holder.messageCard.setOnClickListener {
                onBrainClick(message.id)
            }
        }
    }

    fun setAnalysisResults(logs: List<com.example.myapplication.model.AiAnalysisLog>) {
        analysisMap.clear()
        logs.forEach { analysisMap[it.messageId] = it }
        notifyDataSetChanged()
    }

    fun addAnalysisResult(log: com.example.myapplication.model.AiAnalysisLog) {
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
