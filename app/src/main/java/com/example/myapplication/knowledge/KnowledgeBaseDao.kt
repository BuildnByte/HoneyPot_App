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
}