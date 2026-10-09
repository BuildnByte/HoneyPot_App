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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        sessionAdapter = EvidenceSessionAdapter(
            onClick = { summary ->
                val intent = Intent(this, EvidenceDetailActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", summary.sessionId)
                    putExtra("EXTRA_SOURCE_SENDER", summary.sourceSender)
                }
                startActivity(intent)
            },
            onLongClick = { summary ->
                confirmDeleteSessionEvidence(summary)
            }
        )
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

    // ── Delete helpers ───────────────────────────────────────────────────────────────

    private fun confirmDeleteSessionEvidence(summary: EvidenceSessionSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle("🗑️ Delete Evidence")
            .setMessage(
                "Permanently delete all captured evidence for ‘${summary.sourceSender}’?\n\n" +
                "This will also remove the associated session, messages and analysis logs. " +
                "This action cannot be undone."
            )
            .setPositiveButton("Delete") { _, _ ->
                deleteSessionEvidence(summary.sessionId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSessionEvidence(sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Full cascade — same order as SessionListActivity permanent delete
            db.knowledgeBaseDao().deleteAnalysisLogsForSession(sessionId)
            db.knowledgeBaseDao().deleteEvidenceForSession(sessionId)
            db.knowledgeBaseDao().deleteSimulation(sessionId)
            db.knowledgeBaseDao().deleteSession(sessionId)
            withContext(Dispatchers.Main) { loadSessions() }
        }
    }
}

class EvidenceSessionAdapter(
    private val onClick: (EvidenceSessionSummary) -> Unit,
    private val onLongClick: (EvidenceSessionSummary) -> Unit = {}
) : RecyclerView.Adapter<EvidenceSessionAdapter.ViewHolder>() {

    private var items = listOf<EvidenceSessionSummary>()

    fun setItems(newItems: List<EvidenceSessionSummary>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evidence_session, parent, false)
        return ViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(
        view: View,
        val onClick: (EvidenceSessionSummary) -> Unit,
        val onLongClick: (EvidenceSessionSummary) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val titleText = view.findViewById<TextView>(R.id.sessionTitleText)
        private val subtitleText = view.findViewById<TextView>(R.id.sessionSubtitleText)
        private val dateText = view.findViewById<TextView>(R.id.sessionDateText)
        private val riskBadge = view.findViewById<TextView>(R.id.riskBadge)
        private val reportedBadge = view.findViewById<TextView>(R.id.reportedBadge)
        private val iconCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.sessionIconCard)
        private val iconView = view.findViewById<android.widget.ImageView>(R.id.sessionIconView)

        fun bind(summary: EvidenceSessionSummary) {
            // Mask the phone number for the title
            val rawName = summary.sourceSender
            val isManualCapture = rawName.contains("MANUAL_CAPTURE", ignoreCase = true) || rawName.contains("CAPTURE", ignoreCase = true)
            val title = when {
                isManualCapture -> {
                    val src = if (rawName.contains("whatsapp", true)) "WhatsApp"
                              else if (rawName.contains("telegram", true)) "Telegram"
                              else if (rawName.contains("instagram", true)) "Instagram"
                              else if (rawName.contains("sms", true)) "SMS"
                              else "Screenshot"
                    "$src Analysis"
                }
                rawName.matches(Regex(".*\\d{10,}.*")) -> {
                    val digits = rawName.filter { it.isDigit() }.takeLast(10)
                    "+91 ${digits.substring(0, 5)} ${digits.substring(5)}"
                }
                else -> rawName.removePrefix("Honeypot - ").removePrefix("[CAPTURE] ").removePrefix("[LIVE] ").replace("MANUAL_CAPTURE", "Manual Scan").trim()
            }
            titleText.text = title
            
            subtitleText.text = "${summary.evidenceCount} items captured"
            
            dateText.text = DateUtils.getRelativeTimeSpanString(
                summary.lastTimestamp,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            
            // Bind Reported Status
            if (summary.reportedAt != null) {
                reportedBadge.visibility = View.VISIBLE
            } else {
                reportedBadge.visibility = View.GONE
            }

            // Bind Danger Score / Risk Level
            if (summary.maxConfidence != null) {
                riskBadge.visibility = View.VISIBLE
                val context = riskBadge.context
                when {
                    summary.maxConfidence > 0.8f -> {
                        riskBadge.text = "HIGH RISK"
                        riskBadge.setBackgroundResource(R.drawable.bg_badge_red)
                        riskBadge.setTextColor(context.getColor(R.color.accent_red))
                        iconCard.setCardBackgroundColor(context.getColor(R.color.badge_red_bg))
                        iconView.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_red))
                        iconView.setImageResource(R.drawable.ic_message)
                    }
                    summary.maxConfidence > 0.5f -> {
                        riskBadge.text = "MEDIUM RISK"
                        riskBadge.setBackgroundResource(R.drawable.bg_badge_amber)
                        riskBadge.setTextColor(context.getColor(R.color.accent_amber))
                        iconCard.setCardBackgroundColor(context.getColor(R.color.badge_amber_bg))
                        iconView.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_amber))
                        iconView.setImageResource(R.drawable.ic_sandbox)
                    }
                    else -> {
                        riskBadge.text = "LOW RISK"
                        riskBadge.setBackgroundResource(R.drawable.bg_badge_green)
                        riskBadge.setTextColor(context.getColor(R.color.accent_green))
                        iconCard.setCardBackgroundColor(context.getColor(R.color.badge_green_bg))
                        iconView.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
                        iconView.setImageResource(R.drawable.ic_scan)
                    }
                }
            } else {
                riskBadge.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(summary) }
            itemView.setOnLongClickListener {
                onLongClick(summary)
                true
            }
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
