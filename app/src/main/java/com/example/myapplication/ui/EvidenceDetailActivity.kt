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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.myapplication.export.PdfReportGenerator
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvidenceDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_detail)
        SystemBars.apply(this, findViewById(android.R.id.content))

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

            // Fetch cross-session counts concurrently
            val evidenceWithCounts = evidenceList.map { evidence ->
                async {
                    val senders = if (evidence.entityType != "AMOUNT") {
                        db.knowledgeBaseDao().getCrossSessionSenders(
                            evidence.entityType,
                            evidence.entityValue,
                            sessionId
                        )
                    } else {
                        emptyList()
                    }
                    evidence to senders
                }
            }.awaitAll()

            // Group by entityType, then sort
            val grouped = evidenceWithCounts.groupBy { it.first.entityType }.toSortedMap()

            val displayItems = mutableListOf<DetailDisplayItem>()
            for ((type, items) in grouped) {
                displayItems.add(DetailDisplayItem.Header(type))
                items.forEach { (evidence, senders) ->
                    displayItems.add(DetailDisplayItem.Item(evidence, senders))
                }
            }

            // Build Timeline items
            val history = db.knowledgeBaseDao().getSimulationHistory(sessionId)
            val timelineItems = mutableListOf<TimelineItem>()
            history.forEach { timelineItems.add(TimelineItem.Message(it)) }
            evidenceWithCounts.forEach { timelineItems.add(TimelineItem.Evidence(it.first, it.second)) }
            timelineItems.sortBy { it.timestamp }

            withContext(Dispatchers.Main) {
                val evidenceAdapter = EvidenceDetailAdapter(displayItems)
                val timelineAdapter = TimelineAdapter(timelineItems)

                recyclerView.adapter = evidenceAdapter

                val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.viewToggleGroup)
                toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (isChecked) {
                        when (checkedId) {
                            R.id.btnEvidenceView -> recyclerView.adapter = evidenceAdapter
                            R.id.btnTimelineView -> recyclerView.adapter = timelineAdapter
                        }
                    }
                }

                setupButtons(sessionId, sourceSender, evidenceWithCounts)
            }
        }
    }

    private fun setupButtons(
        sessionId: String, 
        sourceSender: String, 
        evidenceWithCounts: List<Pair<ScamEvidence, List<String>>>
    ) {
        val btnExportReport = findViewById<View>(R.id.btnExportReport)
        val btnReportCybercrime = findViewById<View>(R.id.btnReportCybercrime)

        btnExportReport.setOnClickListener {
            btnExportReport.isEnabled = false
            (btnExportReport as? com.google.android.material.button.MaterialButton)?.text = "Generating PDF…"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = KnowledgeBaseDatabase.getInstance(this@EvidenceDetailActivity)
                    val history = db.knowledgeBaseDao().getSimulationHistory(sessionId)
                    val pdfFile = PdfReportGenerator.generatePdf(
                        this@EvidenceDetailActivity, sessionId, sourceSender, evidenceWithCounts, history
                    )
                    withContext(Dispatchers.Main) {
                        val uri = FileProvider.getUriForFile(
                            this@EvidenceDetailActivity,
                            "${applicationContext.packageName}.fileprovider",
                            pdfFile
                        )
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            type = "application/pdf"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share PDF Crime Report")
                        startActivity(shareIntent)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EvidenceDetailActivity, "Failed to generate PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        btnExportReport.isEnabled = true
                        (btnExportReport as? com.google.android.material.button.MaterialButton)?.text = "Share Crime Report"
                    }
                }
            }
        }

        btnReportCybercrime.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val report = generateReportText(sessionId, sourceSender, evidenceWithCounts)

                // Mark as reported on IO thread before switching to Main
                val db = KnowledgeBaseDatabase.getInstance(this@EvidenceDetailActivity)
                db.knowledgeBaseDao().markSessionReported(sessionId, System.currentTimeMillis())

                withContext(Dispatchers.Main) {
                    // Copy to clipboard
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Cybercrime Report", report)
                    clipboard.setPrimaryClip(clip)
                    
                    Toast.makeText(this@EvidenceDetailActivity, "Report copied to clipboard! Paste it in the portal.", Toast.LENGTH_LONG).show()

                    // Visually confirm by disabling button to prevent double-reports
                    (btnReportCybercrime as? com.google.android.material.button.MaterialButton)?.let {
                        it.text = "Reported"
                        it.isEnabled = false
                    }

                    // Open browser
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in/"))
                    startActivity(intent)
                }
            }
        }
    }

    private suspend fun generateReportText(
        sessionId: String,
        sourceSender: String,
        evidenceWithCounts: List<Pair<ScamEvidence, List<String>>>
    ): String {
        val db = KnowledgeBaseDatabase.getInstance(this)
        val history = db.knowledgeBaseDao().getSimulationHistory(sessionId)
        
        val sb = java.lang.StringBuilder()
        sb.append("🚨 CYBERCRIME INCIDENT REPORT 🚨\n")
        sb.append("==================================\n")
        sb.append("Scammer Identifiers (Sender): $sourceSender\n")
        
        if (history.isNotEmpty()) {
            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(history.first().timestamp))
            sb.append("Incident Date: $dateStr\n")
        }
        sb.append("\n[CAPTURED EVIDENCE]\n")
        
        val grouped = evidenceWithCounts.groupBy { it.first.entityType }
        for ((type, items) in grouped) {
            sb.append("- ${type}s:\n")
            items.forEach { (evidence, senders) ->
                sb.append("  • ${evidence.entityValue}\n") 
                if (senders.isNotEmpty()) {
                    sb.append("    (Also seen in: ${senders.joinToString(", ")})\n")
                }
            }
        }
        
        sb.append("\n[CONVERSATION TRANSCRIPT]\n")
        history.forEach { msg ->
            val sender = if (msg.senderType == "A") "Scammer" else "Victim (App)"
            val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.timestamp))
            sb.append("[$time] $sender: ${msg.content}\n")
        }
        
        sb.append("\n==================================\n")
        sb.append("Report generated by HoneyPot App")
        
        return sb.toString()
    }
}

sealed class DetailDisplayItem {
    data class Header(val type: String) : DetailDisplayItem()
    data class Item(val evidence: ScamEvidence, val crossSessionSenders: List<String>) : DetailDisplayItem()
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
            is DetailDisplayItem.Item -> (holder as ItemViewHolder).bind(item.evidence, item.crossSessionSenders)
        }
    }

    override fun getItemCount() = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerText = view.findViewById<TextView>(R.id.evidenceTypeHeader)
        private val cardContainer = view.findViewById<View>(R.id.evidenceValue).parent as View
        
        fun bind(type: String) {
            headerText.visibility = View.VISIBLE
            headerText.text = "${type.uppercase()}S"
            cardContainer.visibility = View.GONE
        }
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerText = view.findViewById<TextView>(R.id.evidenceTypeHeader)
        private val cardContainer = view.findViewById<View>(R.id.evidenceValue).parent as View
        private val valueText = view.findViewById<TextView>(R.id.evidenceValue)
        private val sourceText = view.findViewById<TextView>(R.id.evidenceSource)
        private val badgeText = view.findViewById<TextView>(R.id.evidenceSeenElsewhere)

        fun bind(evidence: ScamEvidence, crossSessionSenders: List<String>) {
            headerText.visibility = View.GONE
            // Always explicitly set visible to handle recycled views that may have been hidden as headers
            cardContainer.visibility = View.VISIBLE
            
            valueText.text = evidence.entityValue
            sourceText.text = evidence.sourceMessageText
            
            if (crossSessionSenders.isNotEmpty()) {
                badgeText.visibility = View.VISIBLE
                badgeText.text = "🔗 Also seen in: ${crossSessionSenders.joinToString(", ")}"
            } else {
                badgeText.visibility = View.GONE
            }
        }
    }
}
