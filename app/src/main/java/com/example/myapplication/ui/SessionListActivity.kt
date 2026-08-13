package com.example.myapplication.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionListActivity : AppCompatActivity() {

    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fabAddSession)

        sessionAdapter = SessionAdapter(
            onClick = { session ->
                val intent = Intent(this, SimulationActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", session.id)
                }
                startActivity(intent)
            },
            onLongClick = { session ->
                showSessionOptionsDialog(session.id, session.name)
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = sessionAdapter

        fab.setOnClickListener {
            val bottomSheet = CreateSessionBottomSheet { newSession ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = KnowledgeBaseDatabase.getInstance(this@SessionListActivity)
                    db.knowledgeBaseDao().insertSession(newSession)
                    loadSessions()
                }
            }
            bottomSheet.show(supportFragmentManager, "CreateSessionBottomSheet")
        }
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@SessionListActivity)
            val sessions = db.knowledgeBaseDao().getAllSessions()
            
            withContext(Dispatchers.Main) {
                if (sessions.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    sessionAdapter.submitList(emptyList())
                } else {
                    emptyText.visibility = View.GONE
                    sessionAdapter.submitList(sessions)
                }
            }
        }
    }

    private fun showSessionOptionsDialog(sessionId: String, sessionName: String) {
        val options = arrayOf("Archive", "Delete Permanently")
        MaterialAlertDialogBuilder(this)
            .setTitle(sessionName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> archiveSession(sessionId)
                    1 -> confirmDeleteSession(sessionId, sessionName)
                }
            }
            .show()
    }

    private fun archiveSession(sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@SessionListActivity)
            db.knowledgeBaseDao().archiveSession(sessionId)
            loadSessions()
        }
    }

    private fun confirmDeleteSession(sessionId: String, sessionName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Session")
            .setMessage("Are you sure you want to permanently delete '$sessionName' and all its messages? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = KnowledgeBaseDatabase.getInstance(this@SessionListActivity)
                    // Delete associated messages first
                    db.knowledgeBaseDao().deleteSimulation(sessionId)
                    // Then delete the session itself
                    db.knowledgeBaseDao().deleteSession(sessionId)
                    loadSessions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
