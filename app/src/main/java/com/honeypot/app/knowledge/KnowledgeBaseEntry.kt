package com.honeypot.app.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_base")
data class KnowledgeBaseEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val scamType: String,
    val patternDescription: String,
    val exampleTriggerText: String,
    val responseStrategy: String,
    val severity: Int,
    @ColumnInfo(name = "embedding_blob") val embeddingBlob: ByteArray = ByteArray(0)
) {
    // Required because Room uses ByteArray which doesn't support structural equality
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnowledgeBaseEntry) return false
        return id == other.id &&
                scamType == other.scamType &&
                patternDescription == other.patternDescription &&
                exampleTriggerText == other.exampleTriggerText &&
                responseStrategy == other.responseStrategy &&
                severity == other.severity &&
                embeddingBlob.contentEquals(other.embeddingBlob)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + scamType.hashCode()
        result = 31 * result + patternDescription.hashCode()
        result = 31 * result + exampleTriggerText.hashCode()
        result = 31 * result + responseStrategy.hashCode()
        result = 31 * result + severity
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}