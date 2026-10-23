package com.example.data.repository

import com.example.data.db.ChunkDao
import com.example.data.db.ChunkEntity
import com.example.data.db.ConversionLogDao
import com.example.data.db.ConversionLogEntity
import com.example.data.db.ProjectDao
import com.example.data.db.ProjectEntity
import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val projectDao: ProjectDao,
    private val chunkDao: ChunkDao,
    private val logDao: ConversionLogDao
) {
    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()
    val allLogs: Flow<List<ConversionLogEntity>> = logDao.getAllLogs()
    val allChunks: Flow<List<ChunkEntity>> = chunkDao.getAllChunks()

    fun getProject(id: Long): Flow<ProjectEntity?> = projectDao.getProjectById(id)

    suspend fun getProjectDirect(id: Long): ProjectEntity? = projectDao.getProjectByIdDirect(id)

    suspend fun saveProject(project: ProjectEntity): Long {
        return if (project.id == 0L) {
            projectDao.insertProject(project)
        } else {
            projectDao.updateProject(project.copy(updatedAt = System.currentTimeMillis()))
            project.id
        }
    }

    suspend fun deleteProject(project: ProjectEntity) {
        chunkDao.deleteChunksForProject(project.id)
        logDao.deleteLogsForProject(project.id)
        projectDao.deleteProject(project)
    }

    fun getChunks(projectId: Long): Flow<List<ChunkEntity>> = chunkDao.getChunksForProject(projectId)

    suspend fun getChunksDirect(projectId: Long): List<ChunkEntity> =
        chunkDao.getChunksForProjectDirect(projectId)

    suspend fun replaceChunks(projectId: Long, newChunks: List<ChunkEntity>) {
        chunkDao.replaceChunksForProject(projectId, newChunks)
    }

    suspend fun updateProjectLock(projectId: Long, isLocked: Boolean) {
        projectDao.updateProjectLockStatus(projectId, isLocked)
    }

    suspend fun updateChunkStatus(
        chunkId: Long,
        status: String,
        audioPath: String?,
        keyUsedMasked: String?,
        keyUsedName: String?,
        durationMs: Long,
        error: String?
    ) {
        chunkDao.updateChunkStatus(
            chunkId = chunkId,
            status = status,
            audioPath = audioPath,
            keyUsedMasked = keyUsedMasked,
            keyUsedName = keyUsedName,
            durationMs = durationMs,
            error = error
        )
    }

    suspend fun logConversion(
        projectId: Long,
        projectName: String,
        chunkIndex: Int,
        keyName: String,
        keyMasked: String,
        status: String,
        httpCode: Int,
        latencyMs: Long,
        message: String,
        filePath: String? = null,
        fileSize: Long = 0L
    ) {
        logDao.insertLog(
            ConversionLogEntity(
                timestamp = System.currentTimeMillis(),
                projectId = projectId,
                projectName = projectName,
                chunkIndex = chunkIndex,
                keyName = keyName,
                keyMasked = keyMasked,
                status = status,
                httpCode = httpCode,
                latencyMs = latencyMs,
                message = message,
                filePath = filePath,
                fileSize = fileSize
            )
        )
    }

    fun getLogsForProject(projectId: Long): Flow<List<ConversionLogEntity>> =
        logDao.getLogsForProject(projectId)

    suspend fun clearLogs() {
        logDao.clearAllLogs()
    }

    suspend fun clearLogsForProject(projectId: Long) {
        logDao.deleteLogsForProject(projectId)
    }
}
