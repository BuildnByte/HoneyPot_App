package com.example.myapplication.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.model.ScamEvidence
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EvidenceDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_detail)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val sessionId = intent.getStringExtra("EXTRA_SESSION_ID") ?: return
        val sourceSender = intent.getStringExtra("EXTRA_SOURCE_SENDER") ?: ""
        
        supportActionBar?.subtitle = sourceSender

        val recyclerView = findViewById<RecyclerView>(R.id.detailRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@EvidenceDetailActivity)
            val evidenceList = db.knowledgeBaseDao().getEvidenceForSession(sessionId)

            // Group by entityType, then sort
            val grouped = evidenceList.groupBy { it.entityType }.toSortedMap()

            val displayItems = mutableListOf<DetailDisplayItem>()
            for ((type, items) in grouped) {
                displayItems.add(DetailDisplayItem.Header(type))
                items.forEach { evidence ->
                    displayItems.add(DetailDisplayItem.Item(evidence))
                }
            }

            withContext(Dispatchers.Main) {
                recyclerView.adapter = EvidenceDetailAdapter(displayItems)
            }
        }
    }
}

sealed class DetailDisplayItem {
    data class Header(val type: String) : DetailDisplayItem()
    data class Item(val evidence: ScamEvidence) : DetailDisplayItem()
}

class EvidenceDetailAdapter(
    private val items: List<DetailDisplayItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DetailDisplayItem.Header -> TYPE_HEADER
            is DetailDisplayItem.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_evidence_detail, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_evidence_detail, parent, false)
                ItemViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DetailDisplayItem.Header -> (holder as HeaderViewHolder).bind(item.type)
            is DetailDisplayItem.Item -> (holder as ItemViewHolder).bind(item.evidence)
        }
    }

    override fun getItemCount() = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerText = view.findViewById<TextView>(R.id.evidenceTypeHeader)
        private val container = view.findViewById<View>(R.id.evidenceValue).parent as View
        
        fun bind(type: String) {
            headerText.visibility = View.VISIBLE
            headerText.text = "${type.uppercase()}S"
            container.visibility = View.GONE
        }
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerText = view.findViewById<TextView>(R.id.evidenceTypeHeader)
        private val container = view.findViewById<View>(R.id.evidenceValue).parent as View
        private val valueText = view.findViewById<TextView>(R.id.evidenceValue)
        private val sourceText = view.findViewById<TextView>(R.id.evidenceSource)

        fun bind(evidence: ScamEvidence) {
            headerText.visibility = View.GONE
            container.visibility = View.VISIBLE
            
            valueText.text = evidence.entityValue
            sourceText.text = evidence.sourceMessageText
        }
    }
}
