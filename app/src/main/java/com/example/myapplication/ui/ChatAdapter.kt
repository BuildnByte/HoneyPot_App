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
            
            // Ensure the indicator is visible
            holder.brainIndicator.visibility = View.VISIBLE
            
            // Set click listener specifically on the message card for better UX
            holder.messageCard.setOnClickListener {
                onBrainClick(message.id)
            }
        }
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
        val brainIndicator: View = view.findViewById(R.id.brainIndicator)
        val messageCard: View = view.findViewById(R.id.messageCard)
    }
}
