package com.example.myapplication.knowledge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        KnowledgeBaseEntry::class,
        com.example.myapplication.model.SimulatedMessage::class,
        com.example.myapplication.model.AiAnalysisLog::class,
        com.example.myapplication.model.ScamEvidence::class,
        IndicatorEntry::class
    ],
    version = 9,
    exportSchema = false
)
abstract class KnowledgeBaseDatabase : RoomDatabase() {

    abstract fun knowledgeBaseDao(): KnowledgeBaseDao

    companion object {
        @Volatile
        private var INSTANCE: KnowledgeBaseDatabase? = null

        /** Adds the suggestedReply and scamType columns introduced in version 7. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ai_analysis_logs ADD COLUMN suggestedReply TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE ai_analysis_logs ADD COLUMN scamType TEXT NOT NULL DEFAULT 'unknown'"
                )
            }
        }

        /** Adds the scamType column for devices that already ran the v7 migration. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ai_analysis_logs ADD COLUMN scamType TEXT NOT NULL DEFAULT 'unknown'"
                )
            }
        }

        /**
         * Recreates scam_evidence with a UNIQUE index on (sessionId, entityType, entityValue)
         * so duplicate evidence rows are impossible at the DB level.
         * SQLite does not support ALTER TABLE ADD UNIQUE INDEX, so we use the
         * standard copy-rename migration pattern.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scam_evidence_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        sourceSender TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        entityValue TEXT NOT NULL,
                        sourceMessageText TEXT NOT NULL,
                        analysisLogId INTEGER,
                        timestamp INTEGER NOT NULL,
                        UNIQUE(sessionId, entityType, entityValue) ON CONFLICT IGNORE
                    )
                """)
                // Copy existing rows; duplicates are silently dropped by the UNIQUE constraint
                db.execSQL("""
                    INSERT OR IGNORE INTO scam_evidence_new
                    (id, sessionId, sourceSender, entityType, entityValue, sourceMessageText, analysisLogId, timestamp)
                    SELECT id, sessionId, sourceSender, entityType, entityValue, sourceMessageText, analysisLogId, timestamp
                    FROM scam_evidence
                """)
                db.execSQL("DROP TABLE scam_evidence")
                db.execSQL("ALTER TABLE scam_evidence_new RENAME TO scam_evidence")
                // Recreate the index Room expects from the @Index annotation
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_scam_evidence_sessionId_entityType_entityValue
                    ON scam_evidence (sessionId, entityType, entityValue)
                """)
            }
        }

        fun getInstance(context: Context): KnowledgeBaseDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KnowledgeBaseDatabase::class.java,
                    "knowledge_base.db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}