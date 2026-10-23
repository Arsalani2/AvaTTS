package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val rawText: String = "",
    val chunkSize: Int = 500,
    val selectedVoice: String = "Kore",
    val speechRate: Float = 1.0f,
    val folderName: String = "TTS",
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val chunkIndex: Int,
    val text: String,
    val status: String = STATUS_PENDING,
    val audioFilePath: String? = null,
    val keyUsedMasked: String? = null,
    val keyUsedName: String? = null,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(tableName = "conversion_logs")
data class ConversionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val projectId: Long,
    val projectName: String,
    val chunkIndex: Int,
    val keyName: String,
    val keyMasked: String,
    val status: String,
    val httpCode: Int = 200,
    val latencyMs: Long = 0L,
    val message: String = "",
    val filePath: String? = null,
    val fileSize: Long = 0L
)
