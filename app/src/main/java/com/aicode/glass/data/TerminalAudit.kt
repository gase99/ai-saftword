package com.aicode.glass.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Opt-in metadata only: records command names only, never arguments, environment variables, API keys or file content. */
@Singleton
class TerminalAudit @Inject constructor(@ApplicationContext context: Context) {
    private val mutex = Mutex(); private val dir = File(context.filesDir, "terminal-audit")
    suspend fun record(workspace: String, session: String, command: String, exit: Int, duration: Long) = mutex.withLock { withContext(Dispatchers.IO) {
        dir.mkdirs(); val file = File(dir, "$workspace.jsonl")
        val entry = buildJsonObject { put("time", System.currentTimeMillis()); put("session", session); put("command", command); put("exit", exit); put("durationMs", duration) }.toString()
        val retained = if (file.exists()) file.readLines().takeLast(499) else emptyList()
        file.writeText((retained + entry).joinToString("\n") + "\n")
    } }
    suspend fun read(workspace: String): String = mutex.withLock { withContext(Dispatchers.IO) { File(dir, "$workspace.jsonl").let { if(it.exists()) it.readText() else "" } } }
}
