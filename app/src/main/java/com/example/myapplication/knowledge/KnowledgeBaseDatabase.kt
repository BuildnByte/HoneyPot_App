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
        com.example.myapplication.model.SimulationSession::class,
        IndicatorEntry::class,
        com.example.myapplication.manualcapture.model.ManualCapture::class,
        com.example.myapplication.manualcapture.model.ManualCaptureAnalysis::class
    ],
    version = 14,
    exportSchema = false
)
abstract class KnowledgeBaseDatabase : RoomDatabase() {

    abstract fun knowledgeBaseDao(): KnowledgeBaseDao

    companion object {
        @Volatile
        private var INSTANCE: KnowledgeBaseDatabase? = null

        private fun addColumnIfNotExists(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            columnDef: String
        ) {
            val cursor = db.query("PRAGMA table_info($table)")
            var exists = false
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex != -1 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                    exists = true
                    break
                }
            }
            cursor.close()
            if (!exists) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $columnDef")
            }
        }

        /** Adds the suggestedReply and scamType columns introduced in version 7. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "ai_analysis_logs", "suggestedReply", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "ai_analysis_logs", "scamType", "TEXT NOT NULL DEFAULT 'unknown'")
            }
        }

        /** Adds the scamType column for devices that already ran the v7 migration without it. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "ai_analysis_logs", "scamType", "TEXT NOT NULL DEFAULT 'unknown'")
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

        /** Adds suggestedReplySource column to track local vs cloud reply generation. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "ai_analysis_logs", "suggestedReplySource", "TEXT NOT NULL DEFAULT 'local'")
            }
        }

        /** Adds SimulationSession table. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS simulation_sessions (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        attackerPhone TEXT NOT NULL,
                        attackerSenderId TEXT NOT NULL,
                        subscriptionId INTEGER NOT NULL,
                        serviceCenterAddress TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL
                    )
                """)
            }
        }

        /** Adds reportedAt column to simulation_sessions. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "simulation_sessions", "reportedAt", "INTEGER DEFAULT NULL")
            }
        }

        /** Creates the manual_captures and manual_capture_analyses tables. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `manual_captures`")
                db.execSQL("DROP TABLE IF EXISTS `manual_capture_analyses`")
                db.execSQL("DROP INDEX IF EXISTS `index_manual_captures_capturedAt`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `manual_captures` (`id` TEXT NOT NULL, `sourceApp` TEXT NOT NULL, `imagePath` TEXT NOT NULL, `imageSha256` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `status` TEXT NOT NULL, `selectedText` TEXT NOT NULL, `senderLayout` TEXT NOT NULL, `analysisSessionId` TEXT, `errorMessage` TEXT, `viewedAt` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `manual_capture_analyses` (`captureId` TEXT NOT NULL, `isScam` INTEGER NOT NULL, `confidence` REAL NOT NULL, `scamType` TEXT NOT NULL, `reasoning` TEXT NOT NULL, `firedTriggers` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`captureId`))")
            }
        }

        /** Adds safeReply column to manual_capture_analyses. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "manual_capture_analyses", "safeReply", "TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): KnowledgeBaseDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KnowledgeBaseDatabase::class.java,
                    "knowledge_base.db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}