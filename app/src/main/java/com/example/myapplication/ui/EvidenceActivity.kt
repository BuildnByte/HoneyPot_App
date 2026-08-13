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
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EvidenceActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EvidenceSessionAdapter
    private lateinit var db: KnowledgeBaseDatabase
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence) // we reuse the layout but hide FAB

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
        if (emptyText == null) {
            // activity_evidence.xml might not have emptyText, let's just tolerate it if missing
        }

        recyclerView = findViewById(R.id.evidenceRecyclerView)
        adapter = EvidenceSessionAdapter { summary ->
            val intent = Intent(this, EvidenceDetailActivity::class.java).apply {
                putExtra("EXTRA_SESSION_ID", summary.sessionId)
                putExtra("EXTRA_SOURCE_SENDER", summary.sourceSender)
            }
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.knowledgeBaseDao().getEvidenceSessionSummaries()
            
            withContext(Dispatchers.Main) {
                if (sessions.isEmpty()) {
                    emptyText?.visibility = View.VISIBLE
                    emptyText?.text = "No scam evidence captured yet."
                } else {
                    emptyText?.visibility = View.GONE
                }
                adapter.setItems(sessions)
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

        fun bind(summary: EvidenceSessionSummary) {
            senderText.text = summary.sourceSender
            
            dateText.text = DateUtils.getRelativeTimeSpanString(
                summary.lastTimestamp,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            
            countText.text = "${summary.evidenceCount} Evidence Items Captured"

            itemView.setOnClickListener { onClick(summary) }
        }
    }
}
