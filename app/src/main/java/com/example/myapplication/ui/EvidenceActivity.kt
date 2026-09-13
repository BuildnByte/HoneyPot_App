package com.example.myapplication.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.knowledge.EvidenceSessionSummary
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.model.ScamEvidence
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EvidenceActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var sessionAdapter: EvidenceSessionAdapter
    private lateinit var entityAdapter: EvidenceEntityAdapter
    private lateinit var db: KnowledgeBaseDatabase
    private lateinit var emptyText: TextView
    private lateinit var filterTabs: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence) // we reuse the layout but hide FAB
        SystemBars.apply(this, findViewById(android.R.id.content))

        db = KnowledgeBaseDatabase.getInstance(this)

        setupUI()
        loadSessions()
    }

    private fun setupUI() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        emptyText = findViewById<TextView>(R.id.emptyText)

        filterTabs = findViewById(R.id.filterTabs)
        filterTabs.addTab(filterTabs.newTab().setText("All"))
        filterTabs.addTab(filterTabs.newTab().setText("Phones"))
        filterTabs.addTab(filterTabs.newTab().setText("UPI IDs"))
        filterTabs.addTab(filterTabs.newTab().setText("Accounts"))
        filterTabs.addTab(filterTabs.newTab().setText("URLs"))
        filterTabs.addTab(filterTabs.newTab().setText("Amounts"))

        recyclerView = findViewById(R.id.evidenceRecyclerView)
        sessionAdapter = EvidenceSessionAdapter { summary ->
            val intent = Intent(this, EvidenceDetailActivity::class.java).apply {
                putExtra("EXTRA_SESSION_ID", summary.sessionId)
                putExtra("EXTRA_SOURCE_SENDER", summary.sourceSender)
            }
            startActivity(intent)
        }
        entityAdapter = EvidenceEntityAdapter()
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = sessionAdapter

        filterTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        recyclerView.adapter = sessionAdapter
                        loadSessions()
                    }
                    1 -> loadEntities("PHONE")
                    2 -> loadEntities("UPI_ID")
                    3 -> loadEntities("ACCOUNT")
                    4 -> loadEntities("URL")
                    5 -> loadEntities("AMOUNT")
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.knowledgeBaseDao().getEvidenceSessionSummaries()
            
            withContext(Dispatchers.Main) {
                if (sessions.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "No history to show yet."
                } else {
                    emptyText.visibility = View.GONE
                }
                sessionAdapter.setItems(sessions)
            }
        }
    }

    private fun loadEntities(type: String) {
        recyclerView.adapter = entityAdapter
        lifecycleScope.launch(Dispatchers.IO) {
            val entities = db.knowledgeBaseDao().getEvidenceByType(type)
            
            withContext(Dispatchers.Main) {
                if (entities.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "No $type evidence found."
                } else {
                    emptyText.visibility = View.GONE
                }
                entityAdapter.setItems(entities)
            }
        }
    }
}

class EvidenceSessionAdapter(
    private val onClick: (EvidenceSessionSummary) -> Unit
) : RecyclerView.Adapter<EvidenceSessionAdapter.ViewHolder>() {

    private var items = listOf<EvidenceSessionSummary>()

    fun setItems(newItems: List<EvidenceSessionSummary>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evidence_session, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(
        view: View,
        val onClick: (EvidenceSessionSummary) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val senderText = view.findViewById<TextView>(R.id.sessionSender)
        private val dateText = view.findViewById<TextView>(R.id.sessionDate)
        private val countText = view.findViewById<TextView>(R.id.sessionEvidenceCount)
        private val riskBadge = view.findViewById<TextView>(R.id.riskBadge)
        private val reportedBadge = view.findViewById<TextView>(R.id.reportedBadge)

        fun bind(summary: EvidenceSessionSummary) {
            senderText.text = summary.sourceSender
            
            dateText.text = DateUtils.getRelativeTimeSpanString(
                summary.lastTimestamp,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            
            countText.text = "${summary.evidenceCount} Evidence Items Captured"
            
            // Bind Reported Status
            if (summary.reportedAt != null) {
                reportedBadge.visibility = View.VISIBLE
                val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(summary.reportedAt))
                reportedBadge.text = "✓ REPORTED ON ${dateStr.uppercase()}"
            } else {
                reportedBadge.visibility = View.GONE
            }

            // Bind Danger Score
            if (summary.maxConfidence != null) {
                riskBadge.visibility = View.VISIBLE
                when {
                    summary.maxConfidence > 0.8f -> {
                        riskBadge.text = "⚠️ HIGH RISK"
                        riskBadge.setBackgroundColor(riskBadge.context.getColor(R.color.attacker_bubble_scam_bg))
                        riskBadge.setTextColor(riskBadge.context.getColor(R.color.attacker_bubble_scam_stroke))
                    }
                    summary.maxConfidence > 0.5f -> {
                        riskBadge.text = "⚡ MEDIUM RISK"
                        riskBadge.setBackgroundColor(riskBadge.context.getColor(R.color.attacker_bubble_warn_bg))
                        riskBadge.setTextColor(riskBadge.context.getColor(R.color.attacker_bubble_warn_stroke))
                    }
                    else -> {
                        riskBadge.text = "✅ LOW RISK"
                        riskBadge.setBackgroundColor(riskBadge.context.getColor(R.color.attacker_bubble_safe_bg))
                        riskBadge.setTextColor(riskBadge.context.getColor(R.color.attacker_bubble_safe_stroke))
                    }
                }
            } else {
                riskBadge.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(summary) }
        }
    }
}

class EvidenceEntityAdapter : RecyclerView.Adapter<EvidenceEntityAdapter.ViewHolder>() {

    private var items = listOf<ScamEvidence>()

    fun setItems(newItems: List<ScamEvidence>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evidence_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerText = view.findViewById<TextView>(R.id.evidenceTypeHeader)
        private val cardContainer = view.findViewById<View>(R.id.evidenceValue).parent as View
        private val valueText = view.findViewById<TextView>(R.id.evidenceValue)
        private val sourceLabelText = view.findViewById<TextView>(R.id.evidenceSourceLabel)
        private val sourceText = view.findViewById<TextView>(R.id.evidenceSource)
        private val badgeText = view.findViewById<TextView>(R.id.evidenceSeenElsewhere)

        fun bind(evidence: ScamEvidence) {
            headerText.visibility = View.GONE
            cardContainer.visibility = View.VISIBLE
            
            // Update label text since this layout is reused across multiple contexts
            sourceLabelText?.text = "Sender:"
            valueText.text = evidence.entityValue
            sourceText.text = evidence.sourceSender
            badgeText.visibility = View.GONE
        }
    }
}
