package com.aicode.glass.data

import android.content.Context
import com.aicode.glass.core.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckpointRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CheckpointDao, private val workspaces: WorkspaceRepository
) {
    private val objects = File(context.filesDir, "checkpoint-objects")
    private val mutex = Mutex()
    fun observe(workspaceId: String) = dao.observe(workspaceId)
    suspend fun capture(workspaceId: String, label: String): String = workspaces.transaction(workspaceId) { captureLocked(it, workspaceId, label) }

    /** Caller must hold WorkspaceRepository.transaction for this workspace. */
    suspend fun captureLocked(root: File, workspaceId: String, label: String): String = mutex.withLock {
        objects.mkdirs()
        val files = workspaces.walk(root).map { file ->
            val bytes = file.readBytes(); val hash = WorkspaceRepository.sha(bytes)
            val blob = File(objects, hash)
            if (!blob.exists()) WorkspaceRepository.atomicWrite(blob, bytes)
            CheckpointFileEntity("", file.relativeTo(root).invariantSeparatorsPath, hash, bytes.size.toLong())
        }
        val checkpoint = CheckpointEntity(UUID.randomUUID().toString(), workspaceId, label.take(120), System.currentTimeMillis())
        dao.insertSnapshot(checkpoint, files.map { it.copy(checkpointId = checkpoint.id) })
        dao.all(workspaceId).drop(30).forEach { dao.delete(it.id) }
        val referenced = dao.referencedHashes().toSet()
        objects.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
        checkpoint.id
    }

    suspend fun restore(checkpointId: String) = withContext(Dispatchers.IO) {
        val checkpoint = dao.get(checkpointId) ?: throw WorkspaceException("Checkpoint no longer exists.")
        val files = dao.files(checkpointId)
        workspaces.transaction(checkpoint.workspaceId) { root ->
            // A safety checkpoint makes a rollback itself reversible.
            captureLocked(root, checkpoint.workspaceId, "Before rollback")
            val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}"); staging.mkdirs()
            try {
                files.forEach { entry ->
                    require(entry.hash.matches(Regex("[0-9a-f]{64}")))
                    val target = workspaces.resolve(staging, entry.path)
                    val blob = File(objects, entry.hash)
                    if (!blob.isFile || WorkspaceRepository.hash(blob) != entry.hash) throw WorkspaceException("Checkpoint data is missing or damaged. No files were changed.")
                    target.parentFile?.mkdirs(); blob.copyTo(target)
                }
                WorkspaceRepository.swapContents(root, staging)
            } finally { staging.deleteRecursively() }
        }
    }
}
