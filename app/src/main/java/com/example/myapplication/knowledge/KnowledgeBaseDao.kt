package com.example.myapplication.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.myapplication.model.AiAnalysisLog
import com.example.myapplication.model.ScamEvidence
import com.example.myapplication.model.SimulatedMessage
import com.example.myapplication.model.SimulationSession
import com.example.myapplication.manualcapture.model.ManualCapture
import com.example.myapplication.manualcapture.model.ManualCaptureAnalysis
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeBaseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<KnowledgeBaseEntry>)

    @Query("SELECT * FROM knowledge_base")
    suspend fun getAll(): List<KnowledgeBaseEntry>

    @Query("SELECT * FROM knowledge_base WHERE scamType = :type")
    suspend fun getByType(type: String): List<KnowledgeBaseEntry>

    @Query("SELECT COUNT(*) FROM knowledge_base")
    suspend fun count(): Int

    // Simulation Session related
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SimulationSession)

    @Query("SELECT * FROM simulation_sessions WHERE isArchived = 0 AND name NOT LIKE '[LIVE] %' AND name NOT LIKE '[CAPTURE] %' ORDER BY createdAt DESC")
    suspend fun getSimulatedSessions(): List<SimulationSession>

    @Query("SELECT * FROM simulation_sessions WHERE isArchived = 0 AND name LIKE '[CAPTURE] %' ORDER BY createdAt DESC")
    suspend fun getOverlayCaptureSessions(): List<SimulationSession>

    @Query("SELECT * FROM simulation_sessions WHERE isArchived = 0 AND name LIKE '[LIVE] %' ORDER BY createdAt DESC")
    suspend fun getLiveSessions(): List<SimulationSession>

    @Query("SELECT * FROM simulation_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): SimulationSession?

    @Query("SELECT * FROM simulation_sessions WHERE attackerPhone = :phone AND name LIKE '[LIVE] %' LIMIT 1")
    suspend fun getLiveSessionByAttackerPhone(phone: String): SimulationSession?

    @Update
    suspend fun updateSession(session: SimulationSession)

    @Query("UPDATE simulation_sessions SET isArchived = 1 WHERE id = :id")
    suspend fun archiveSession(id: String)

    @Query("DELETE FROM simulation_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    // Simulation Message related
    @Insert
    suspend fun insertSimulatedMessage(message: SimulatedMessage): Long

    @Query("SELECT * FROM simulated_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSimulationHistory(sessionId: String): List<SimulatedMessage>

    /**
     * Reactive version of [getSimulationHistory].
     * Room emits a new list every time any row in simulated_messages for this session changes.
     * Used by HoneypotConversationActivity to update the chat UI in real-time without polling.
     */
    @Query("SELECT * FROM simulated_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeSessionMessages(sessionId: String): Flow<List<SimulatedMessage>>

    @Insert
    suspend fun insertAiAnalysisLog(log: AiAnalysisLog)

    @Query("SELECT * FROM ai_analysis_logs WHERE messageId = :messageId LIMIT 1")
    suspend fun getAnalysisForMessage(messageId: Int): AiAnalysisLog?

    /**
     * Reactively emits the most recent AiAnalysisLog that has a non-empty suggestedReply
     * for any message in this session. Used to auto-surface the suggestion bar whenever
     * SmsProcessingService finishes analysing a follow-up message without user interaction.
     */
    @Query("""
        SELECT a.* FROM ai_analysis_logs a
        INNER JOIN simulated_messages m ON a.messageId = m.id
        WHERE m.sessionId = :sessionId
          AND a.suggestedReply != ''
        ORDER BY a.timestamp DESC
        LIMIT 1
    """)
    fun observeLatestSuggestedReply(sessionId: String): Flow<AiAnalysisLog?>

    @Query("DELETE FROM simulated_messages WHERE sessionId = :sessionId")
    suspend fun deleteSimulation(sessionId: String)

    /**
     * Deletes all AI analysis logs whose parent message belongs to this session.
     * Called alongside [deleteSimulation] and [deleteEvidenceForSession] when a session
     * is permanently deleted, so no orphaned rows accumulate in the database.
     */
    @Query("""
        DELETE FROM ai_analysis_logs
        WHERE messageId IN (
            SELECT id FROM simulated_messages WHERE sessionId = :sessionId
        )
    """)
    suspend fun deleteAnalysisLogsForSession(sessionId: String)

    /** Deletes all scam evidence captured for a session. */
    @Query("DELETE FROM scam_evidence WHERE sessionId = :sessionId")
    suspend fun deleteEvidenceForSession(sessionId: String)

    // Evidence related
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(evidence: ScamEvidence)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidenceBatch(evidence: List<ScamEvidence>)

    @Query("SELECT * FROM scam_evidence ORDER BY timestamp DESC")
    suspend fun getAllEvidence(): List<ScamEvidence>

    @Query("""
        SELECT 
            e.sessionId, 
            e.sourceSender, 
            MAX(e.timestamp) as lastTimestamp, 
            COUNT(e.id) as evidenceCount,
            s.reportedAt,
            (SELECT MAX(c) FROM (
                SELECT MAX(a.confidence) as c FROM ai_analysis_logs a INNER JOIN simulated_messages m ON a.messageId = m.id WHERE m.sessionId = e.sessionId
                UNION ALL
                SELECT MAX(ma.confidence) as c FROM manual_capture_analyses ma INNER JOIN manual_captures mc ON ma.captureId = mc.id WHERE mc.analysisSessionId = e.sessionId
            )) as maxConfidence
        FROM scam_evidence e
        LEFT JOIN simulation_sessions s ON e.sessionId = s.id
        GROUP BY e.sessionId, e.sourceSender, s.reportedAt
        ORDER BY lastTimestamp DESC
    """)
    suspend fun getEvidenceSessionSummaries(): List<EvidenceSessionSummary>

    @Query("UPDATE simulation_sessions SET reportedAt = :timestamp WHERE id = :sessionId")
    suspend fun markSessionReported(sessionId: String, timestamp: Long)

    @Query("SELECT * FROM scam_evidence WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEvidenceForSession(sessionId: String): List<ScamEvidence>

    @Query("SELECT COUNT(DISTINCT sessionId) FROM scam_evidence WHERE entityType = :entityType AND entityValue = :entityValue AND sessionId != :currentSessionId")
    suspend fun getCrossSessionCount(entityType: String, entityValue: String, currentSessionId: String): Int

    @Query("SELECT DISTINCT sourceSender FROM scam_evidence WHERE entityType = :entityType AND entityValue = :entityValue AND sessionId != :currentSessionId")
    suspend fun getCrossSessionSenders(entityType: String, entityValue: String, currentSessionId: String): List<String>

    @Query("SELECT * FROM scam_evidence WHERE entityType = :type ORDER BY timestamp DESC")
    suspend fun getEvidenceByType(type: String): List<ScamEvidence>

    @Query("SELECT * FROM scam_evidence WHERE sourceSender = :sender ORDER BY timestamp DESC")
    suspend fun getEvidenceForSender(sender: String): List<ScamEvidence>

    @Query("DELETE FROM scam_evidence WHERE id = :id")
    suspend fun deleteEvidence(id: Int)

    // Indicator related
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIndicator(entry: IndicatorEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIndicators(entries: List<IndicatorEntry>)

    @Query("SELECT * FROM indicator_kb WHERE indicatorValue = :value LIMIT 1")
    suspend fun getIndicator(value: String): IndicatorEntry?

    @Query("SELECT * FROM indicator_kb WHERE indicatorType = :type")
    suspend fun getIndicatorsByType(type: String): List<IndicatorEntry>

    @Query("SELECT * FROM scam_evidence WHERE entityValue = :value")
    suspend fun getEvidenceMatchingValue(value: String): List<ScamEvidence>

    /**
     * Returns the first AI analysis log where isScam=1 for a given simulation session.
     * Used to detect whether the session has already been confirmed as a scam,
     * so subsequent attacker messages can skip re-classification.
     */
    @Query("""
        SELECT a.* FROM ai_analysis_logs a
        INNER JOIN simulated_messages m ON a.messageId = m.id
        WHERE m.sessionId = :sessionId AND a.isScam = 1
        ORDER BY a.timestamp ASC
        LIMIT 1
    """)
    suspend fun getFirstScamLogForSession(sessionId: String): AiAnalysisLog?

    // ── Manual Capture ─────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManualCapture(capture: ManualCapture)

    @Update
    suspend fun updateManualCapture(capture: ManualCapture)

    @Query("SELECT * FROM manual_captures WHERE id = :id LIMIT 1")
    suspend fun getManualCapture(id: String): ManualCapture?

    @Query("SELECT * FROM manual_captures ORDER BY capturedAt DESC")
    suspend fun getAllManualCaptures(): List<ManualCapture>

    @Query("SELECT * FROM manual_captures WHERE status NOT IN ('DISCARDED','FAILED') ORDER BY capturedAt DESC")
    suspend fun getActiveManualCaptures(): List<ManualCapture>

    @Query("UPDATE manual_captures SET status = :status WHERE id = :id")
    suspend fun updateManualCaptureStatus(id: String, status: String)

    @Query("UPDATE manual_captures SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateManualCaptureStatusWithError(id: String, status: String, error: String)

    @Query("UPDATE manual_captures SET selectedText = :text, senderLayout = :layout, status = 'REVIEWED' WHERE id = :id")
    suspend fun updateManualCaptureReview(id: String, text: String, layout: String)

    @Query("UPDATE manual_captures SET analysisSessionId = :sessionId WHERE id = :id")
    suspend fun updateManualCaptureSessionId(id: String, sessionId: String)

    @Query("UPDATE manual_captures SET imagePath = :path WHERE id = :id")
    suspend fun updateManualCaptureImagePath(id: String, path: String)

    @Query("UPDATE manual_captures SET viewedAt = :viewedAt WHERE id = :id")
    suspend fun markManualCaptureViewed(id: String, viewedAt: Long)

    @Query("DELETE FROM manual_captures WHERE id = :id")
    suspend fun deleteManualCapture(id: String)

    // ── Manual Capture Analysis ─────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManualCaptureAnalysis(analysis: ManualCaptureAnalysis)

    @Query("SELECT * FROM manual_capture_analyses WHERE captureId = :captureId LIMIT 1")
    suspend fun getManualCaptureAnalysis(captureId: String): ManualCaptureAnalysis?

    @Query("DELETE FROM manual_capture_analyses WHERE captureId = :captureId")
    suspend fun deleteManualCaptureAnalysis(captureId: String)

    // ── App Statistics & Metrics ────────────────────────────────────────────────
    @Query("SELECT COUNT(*) FROM manual_captures")
    suspend fun getManualCapturesCount(): Int

    @Query("""
        SELECT COUNT(*) FROM simulated_messages m
        INNER JOIN simulation_sessions s ON m.sessionId = s.id
        WHERE m.senderType = 'A'
          AND s.name NOT LIKE '[LIVE] %'
          AND s.name NOT LIKE '[CAPTURE] %'
    """)
    suspend fun getSandboxAttackerMessagesCount(): Int

    @Query("SELECT * FROM manual_capture_analyses WHERE isScam = 1")
    suspend fun getScamManualCaptureAnalyses(): List<ManualCaptureAnalysis>

    @Query("SELECT * FROM ai_analysis_logs WHERE isScam = 1")
    suspend fun getAllScamAiLogs(): List<AiAnalysisLog>

    @Query("""
        SELECT DISTINCT m.sessionId FROM simulated_messages m
        INNER JOIN ai_analysis_logs a ON m.id = a.messageId
        WHERE a.isScam = 1
    """)
    suspend fun getScamAiSessionIds(): List<String>

    @Query("""
        SELECT DISTINCT m.sessionId FROM simulated_messages m
        INNER JOIN ai_analysis_logs a ON m.id = a.messageId
        WHERE a.isScam = 1 AND a.timestamp >= :sinceTimestamp
    """)
    suspend fun getScamAiSessionIdsSince(sinceTimestamp: Long): List<String>
}

data class EvidenceSessionSummary(
    val sessionId: String,
    val sourceSender: String,
    val lastTimestamp: Long,
    val evidenceCount: Int,
    val maxConfidence: Float?,
    val reportedAt: Long?
)