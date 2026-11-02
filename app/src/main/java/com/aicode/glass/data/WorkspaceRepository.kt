package com.aicode.glass.data

import android.content.Context
import com.aicode.glass.core.data.WorkspaceDao
import com.aicode.glass.core.data.WorkspaceEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class FileItem(val path: String, val directory: Boolean, val size: Long)
data class WorkspaceFile(val path: String, val content: String, val hash: String)
class WorkspaceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** All workspace mutations are serialized, including terminal, AI, editor and Git. */
@Singleton
class WorkspaceRepository @Inject constructor(
    @ApplicationContext private val context: Context, private val dao: WorkspaceDao
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    val workspaces: Flow<List<WorkspaceEntity>> = dao.observe()
    private fun validId(id: String): String {
        require(id == "default" || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid workspace ID" }
        return id
    }
    fun root(id: String): File = File(context.filesDir, "workspaces/${validId(id)}").canonicalFile
    suspend fun <T> transaction(id: String, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        locks.getOrPut(validId(id)) { Mutex() }.withLock { block(root(id)) }
    }
    suspend fun ensureDefault() = withContext(Dispatchers.IO) {
        if (dao.get("default") == null) {
            val root = root("default"); root.mkdirs()
            File(root, "README.md").writeText("# My mobile workspace\n\nEdit files, ask AI for help, and create checkpoints before experimenting.\n")
            File(root, "hello.py").writeText("def greet(name):\n    return f\"Hello, {name}!\"\n\nprint(greet(\"AiCode Glass\"))\n")
            File(root, ".gitignore").writeText("__pycache__/\n*.log\n.env\nnode_modules/\nbuild/\n")
            dao.insert(WorkspaceEntity("default", "My workspace", System.currentTimeMillis()))
        }
    }
    suspend fun create(name: String): String = withContext(Dispatchers.IO) {
        val clean = name.trim().take(60).ifBlank { throw WorkspaceException("Enter a workspace name.") }
        val id = UUID.randomUUID().toString(); root(id).mkdirs()
        dao.insert(WorkspaceEntity(id, clean, System.currentTimeMillis()))
        id
    }
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Enter a workspace name." }; dao.rename(id, name.trim().take(60))
    }
    fun resolve(root: File, relative: String): File {
        if (relative.length > 500 || relative.startsWith("/") || relative.contains("\\") || relative.contains("\u0000")) throw WorkspaceException("Invalid relative path.")
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." || it.equals(".git", true) }) throw WorkspaceException("Use the Git tab for repository metadata; traversal is not allowed.")
        val base = root.canonicalFile.toPath()
        var current = base
        for (part in parts) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw WorkspaceException("Symbolic links are not supported.")
        }
        val target = current.normalize()
        if (!target.startsWith(base)) throw WorkspaceException("Path is outside the workspace.")
        return target.toFile()
    }
    fun list(root: File, directory: String = ""): List<FileItem> {
        val parent = resolve(root, directory)
        if (!parent.exists()) return emptyList()
        if (!parent.isDirectory) throw WorkspaceException("Not a directory.")
        return (parent.listFiles() ?: emptyArray()).filter { it.name != ".git" && !Files.isSymbolicLink(it.toPath()) }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .take(2000).map { FileItem(if (directory.isBlank()) it.name else "$directory/${it.name}", it.isDirectory, it.length()) }
    }
    fun hash(file: File): String = sha(file.readBytes())
    fun read(root: File, path: String): WorkspaceFile {
        val file = resolve(root, path)
        if (!file.isFile) throw WorkspaceException("File not found.")
        if (file.length() > MAX_FILE) throw WorkspaceException("Files larger than 1 MB cannot be opened in the editor.")
        val bytes = file.readBytes()
        if (bytes.take(8000).contains(0.toByte())) throw WorkspaceException("Binary files are not supported by the text editor.")
        val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return WorkspaceFile(path, content, sha(bytes))
    }
    suspend fun read(id: String, path: String): WorkspaceFile = transaction(id) { read(it, path) }
    fun write(root: File, path: String, content: String, expectedHash: String? = null) {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE) throw WorkspaceException("The editor supports files up to 1 MB.")
        val file = resolve(root, path)
        if (expectedHash != null && (!file.exists() || hash(file) != expectedHash)) throw WorkspaceException("This file changed elsewhere. Reopen it before saving to avoid overwriting changes.")
        file.parentFile?.mkdirs(); atomicWrite(file, content.toByteArray(Charsets.UTF_8))
    }
    fun delete(root: File, path: String) {
        val file = resolve(root, path)
        if (file == root.canonicalFile || !file.exists()) throw WorkspaceException("Cannot delete this path.")
        if (!file.deleteRecursively()) throw WorkspaceException("Could not delete the file.")
    }
    fun walk(root: File): List<File> = root.walkTopDown().onEnter { it == root || it.name != ".git" }
        .filter { it.isFile }.also { files ->
            if (files.size > MAX_FILES) throw WorkspaceException("Workspace exceeds the 4,000-file snapshot limit.")
            if (files.sumOf { it.length() } > MAX_TOTAL) throw WorkspaceException("Workspace exceeds the 64 MB snapshot limit.")
        }.map { it.canonicalFile.along(root) }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    private fun File.along(root: File): File { if (!canonicalPath.startsWith(root.canonicalPath + File.separator)) throw WorkspaceException("A file points outside the workspace."); return this }
    suspend fun importZip(id: String, input: InputStream, replace: suspend (File, File) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File.createTempFile("import-", ".zip", context.cacheDir)
        val staging = File(context.cacheDir, "import-${UUID.randomUUID()}"); staging.mkdirs()
        try {
            archive.outputStream().use { out ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                    if (total > MAX_TOTAL) throw WorkspaceException("Archive exceeds 64 MB.")
                    out.write(buffer, 0, n)
                }
            }
            var count = 0; var total = 0L
            java.util.zip.ZipFile(archive).use { zip ->
                for (entry in zip.entries()) {
                    if (++count > MAX_FILES) throw WorkspaceException("Archive contains too many files.")
                    val mode = (entry.externalAttributes ushr 16) and 0xF000
                    if (mode == 0xA000) throw WorkspaceException("Archive symbolic links are not allowed.")
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith(".git/") || name.contains("/.git/")) continue
                    if (entry.size > MAX_FILE) throw WorkspaceException("Archive contains a file larger than 1 MB.")
                    val target = resolve(staging, name); target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { source -> source.copyTo(target.outputStream()) }
                    total += target.length(); if (total > MAX_TOTAL) throw WorkspaceException("Archive exceeds 64 MB.")
                }
            }
            if (count == 0) throw WorkspaceException("The archive is empty.")
            replace(staging, root(id))
        } finally { staging.deleteRecursively(); archive.delete() }
    }
    fun export(root: File, output: OutputStream) = ZipOutputStream(output.buffered()).use { zip ->
        walk(root).forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
    }
    companion object {
        const val MAX_FILE = 1024 * 1024L; const val MAX_TOTAL = 64L * 1024 * 1024; const val MAX_FILES = 4000
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun atomicWrite(target: File, bytes: ByteArray) {
            val temp = File.createTempFile(".glass-", ".tmp", target.parentFile)
            try { temp.outputStream().use { it.write(bytes); it.fd.sync() }
                try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { temp.delete() }
        }
        fun swapContents(root: File, staging: File) {
            val backup = File(root.parentFile, ".backup-${UUID.randomUUID()}")
            if (!root.renameTo(backup)) throw WorkspaceException("Could not prepare rollback.")
            try {
                if (!staging.renameTo(root)) throw WorkspaceException("Could not activate restored workspace.")
                val git = File(backup, ".git")
                if (git.exists() && !git.renameTo(File(root, ".git"))) throw WorkspaceException("Could not preserve Git metadata.")
            } catch (error: Exception) {
                if (root.exists()) root.renameTo(File(root.parentFile, ".failed-${UUID.randomUUID()}"))
                check(backup.renameTo(root)) { "Rollback recovery failed; the backup is retained at $backup" }
                throw error
            }
            backup.deleteRecursively()
        }
    }
}
