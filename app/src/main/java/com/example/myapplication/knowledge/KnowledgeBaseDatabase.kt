package com.example.myapplication.knowledge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        KnowledgeBaseEntry::class,
        com.example.myapplication.model.SimulatedMessage::class,
        com.example.myapplication.model.AiAnalysisLog::class,
        com.example.myapplication.model.ScamEvidence::class,
        IndicatorEntry::class
    ],
    version = 6,
    exportSchema = false
)
abstract class KnowledgeBaseDatabase : RoomDatabase() {

    abstract fun knowledgeBaseDao(): KnowledgeBaseDao

    companion object {
        @Volatile
        private var INSTANCE: KnowledgeBaseDatabase? = null

        fun getInstance(context: Context): KnowledgeBaseDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KnowledgeBaseDatabase::class.java,
                    "knowledge_base.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}