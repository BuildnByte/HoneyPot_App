package com.example.myapplication.knowledge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [KnowledgeBaseEntry::class], version = 1, exportSchema = false)
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