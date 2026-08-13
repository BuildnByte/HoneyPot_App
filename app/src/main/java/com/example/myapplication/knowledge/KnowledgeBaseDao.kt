package com.example.myapplication.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    // Simulation related
    @Insert
    suspend fun insertSimulatedMessage(message: com.example.myapplication.model.SimulatedMessage): Long

    @Query("SELECT * FROM simulated_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSimulationHistory(sessionId: String): List<com.example.myapplication.model.SimulatedMessage>

    @Insert
    suspend fun insertAiAnalysisLog(log: com.example.myapplication.model.AiAnalysisLog)

    @Query("SELECT * FROM ai_analysis_logs WHERE messageId = :messageId LIMIT 1")
    suspend fun getAnalysisForMessage(messageId: Int): com.example.myapplication.model.AiAnalysisLog?

    @Query("DELETE FROM simulated_messages WHERE sessionId = :sessionId")
    suspend fun deleteSimulation(sessionId: String)

    // Evidence related
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(evidence: com.example.myapplication.model.ScamEvidence)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidenceBatch(evidence: List<com.example.myapplication.model.ScamEvidence>)

    @Query("SELECT * FROM scam_evidence ORDER BY timestamp DESC")
    suspend fun getAllEvidence(): List<com.example.myapplication.model.ScamEvidence>

    @Query("SELECT * FROM scam_evidence WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEvidenceForSession(sessionId: String): List<com.example.myapplication.model.ScamEvidence>

    @Query("SELECT * FROM scam_evidence WHERE entityType = :type ORDER BY timestamp DESC")
    suspend fun getEvidenceByType(type: String): List<com.example.myapplication.model.ScamEvidence>

    @Query("SELECT * FROM scam_evidence WHERE sourceSender = :sender ORDER BY timestamp DESC")
    suspend fun getEvidenceForSender(sender: String): List<com.example.myapplication.model.ScamEvidence>

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
    suspend fun getEvidenceMatchingValue(value: String): List<com.example.myapplication.model.ScamEvidence>

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
    suspend fun getFirstScamLogForSession(sessionId: String): com.example.myapplication.model.AiAnalysisLog?
}