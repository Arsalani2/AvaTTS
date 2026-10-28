package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun getProjectById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectByIdDirect(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)

    @Query("UPDATE projects SET isLocked = :isLocked, updatedAt = :now WHERE id = :id")
    suspend fun updateProjectLockStatus(id: Long, isLocked: Boolean, now: Long = System.currentTimeMillis())
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks ORDER BY chunkIndex ASC")
    fun getAllChunks(): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE projectId = :projectId ORDER BY chunkIndex ASC")
    fun getChunksForProject(projectId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE projectId = :projectId ORDER BY chunkIndex ASC")
    suspend fun getChunksForProjectDirect(projectId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id = :id LIMIT 1")
    suspend fun getChunkById(id: Long): ChunkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Update
    suspend fun updateChunk(chunk: ChunkEntity)

    @Query("DELETE FROM chunks WHERE projectId = :projectId")
    suspend fun deleteChunksForProject(projectId: Long)

    @Transaction
    suspend fun replaceChunksForProject(projectId: Long, newChunks: List<ChunkEntity>) {
        deleteChunksForProject(projectId)
        insertChunks(newChunks)
    }

    @Query("""
        UPDATE chunks
        SET status = :status,
            audioFilePath = :audioPath,
            keyUsedMasked = :keyUsedMasked,
            keyUsedName = :keyUsedName,
            durationMs = :durationMs,
            errorMessage = :error,
            updatedAt = :now
        WHERE id = :chunkId
    """)
    suspend fun updateChunkStatus(
        chunkId: Long,
        status: String,
        audioPath: String?,
        keyUsedMasked: String?,
        keyUsedName: String?,
        durationMs: Long,
        error: String?,
        now: Long = System.currentTimeMillis()
    )

    // BUG-41: چانکهایی که هنگام مرگ پروسه (force-stop/OOM/کرش) در وضعیت PROCESSING
    // گیر کرده‌اند، در نخستین ساخت engine (استارتاپ بعدی) به PENDING برمی‌گردند.
    @Query("""
        UPDATE chunks
        SET status = :pendingStatus,
            audioFilePath = NULL,
            keyUsedMasked = NULL,
            keyUsedName = NULL,
            durationMs = 0,
            errorMessage = NULL,
            updatedAt = :now
        WHERE status = :processingStatus
    """)
    suspend fun resetProcessingChunks(
        pendingStatus: String = ChunkEntity.STATUS_PENDING,
        processingStatus: String = ChunkEntity.STATUS_PROCESSING,
        now: Long = System.currentTimeMillis()
    ): Int
}

@Dao
interface ConversionLogDao {
    @Query("SELECT * FROM conversion_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllLogs(): Flow<List<ConversionLogEntity>>

    @Query("SELECT * FROM conversion_logs WHERE projectId = :projectId ORDER BY timestamp DESC LIMIT 200")
    fun getLogsForProject(projectId: Long): Flow<List<ConversionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ConversionLogEntity): Long

    @Query("DELETE FROM conversion_logs")
    suspend fun clearAllLogs()

    @Query("DELETE FROM conversion_logs WHERE projectId = :projectId")
    suspend fun deleteLogsForProject(projectId: Long)
}
