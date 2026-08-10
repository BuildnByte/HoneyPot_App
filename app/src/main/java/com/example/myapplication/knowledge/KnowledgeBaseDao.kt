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
}