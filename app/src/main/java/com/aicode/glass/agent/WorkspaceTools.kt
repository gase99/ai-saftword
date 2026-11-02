package com.aicode.glass.agent

import com.aicode.glass.core.network.*
import com.aicode.glass.data.*
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ToolResult(val content: String, val isError: Boolean = false, val checkpointId: String? = null)
data class ToolPreview(val path: String, val before: String, val after: String)

@Singleton
class WorkspaceTools @Inject constructor(private val workspaces: WorkspaceRepository, private val checkpoints: CheckpointRepository, private val git: GitRepository) {
    companion object {
        val READ_ONLY = setOf("list_files", "read_file", "search_files", "git_status", "git_diff")
        val WRITES = setOf("write_file", "replace_text")
        val specs = listOf(
            ToolSpec("list_files", "List a local workspace directory. Paths are relative; Git metadata is excluded.", JsonTools.schema(mapOf("path" to "string"), emptyList())),
            ToolSpec("read_file", "Read a UTF-8 workspace file, up to 1 MB.", JsonTools.schema(mapOf("path" to "string"), listOf("path"))),
            ToolSpec("search_files", "Case-insensitive literal search across workspace text files; returns at most 80 matches.", JsonTools.schema(mapOf("query" to "string"), listOf("query"))),
            ToolSpec("git_status", "Read real local Git status and current branch.", JsonTools.schema(emptyMap(), emptyList())),
            ToolSpec("git_diff", "Read a file's real Git diff.", JsonTools.schema(mapOf("path" to "string", "staged" to "boolean"), listOf("path"))),
            ToolSpec("write_file", "Create or replace a UTF-8 file. Requires explicit user approval and creates a checkpoint.", JsonTools.schema(mapOf("path" to "string", "content" to "string"), listOf("path", "content"))),
            ToolSpec("replace_text", "Replace one exact, unique occurrence of text in a file. Requires user approval and creates a checkpoint.", JsonTools.schema(mapOf("path" to "string", "old_text" to "string", "new_text" to "string"), listOf("path", "old_text", "new_text")))
        )
    }
    fun preview(workspaceId: String, call: ToolCall): ToolPreview {
        val args = validatedArgs(call)
        val path = args.getValue("path").jsonPrimitive.content
        val root = workspaces.root(workspaceId)
        val before = runCatching { workspaces.read(root, path).content }.getOrDefault("")
        val after = if (call.name == "write_file") args.getValue("content").jsonPrimitive.content else {
            val old = args.getValue("old_text").jsonPrimitive.content
            val new = args.getValue("new_text").jsonPrimitive.content
            require(old.isNotEmpty() && before.split(old).size == 2) { "old_text must occur exactly once." }
            before.replace(old, new)
        }
        return ToolPreview(path, before, after)
    }
    private fun validatedArgs(call: ToolCall): JsonObject {
        val spec = specs.find { it.name == call.name } ?: throw IllegalArgumentException("Unknown tool")
        val args = JsonTools.parseArgs(call.arguments)
        val properties = spec.parameters.getValue("properties").jsonObject.keys
        require(args.keys.all { it in properties }) { "Unexpected tool arguments" }
        spec.parameters.getValue("required").jsonArray.forEach { require(it.jsonPrimitive.content in args) { "Missing tool argument: $it" } }
        properties.forEach { name -> args[name]?.let { value ->
            require(value is JsonPrimitive && value.isString) { "$name must be a string" }
        } }
        require(call.arguments.length <= 2 * WorkspaceRepository.MAX_FILE) { "Tool arguments too large" }
        return args
    }
    suspend fun execute(workspaceId: String, call: ToolCall, mode: String, approved: Boolean): ToolResult {
        if (call.name !in READ_ONLY + WRITES) return ToolResult("Tool is not enabled.", true)
        if (call.name in WRITES && (mode == "ask" || !approved)) return ToolResult("Write denied: explicit approval is required outside Ask mode.", true)
        return try {
            val args = validatedArgs(call)
            fun path() = args.getValue("path").jsonPrimitive.content
            when (call.name) {
                "list_files" -> ToolResult(workspaces.transaction(workspaceId) { root -> workspaces.list(root, args["path"]?.jsonPrimitive?.content ?: "").joinToString("\n") { "${if (it.directory) "dir " else "file"} ${it.path} (${it.size} bytes)" }.ifBlank { "Empty directory" } })
                "read_file" -> ToolResult(workspaces.read(workspaceId, path()).content)
                "search_files" -> {
                    val query = args.getValue("query").jsonPrimitive.content
                    require(query.isNotBlank() && query.length <= 300) { "Query must contain 1–300 characters." }
                    ToolResult(workspaces.transaction(workspaceId) { root ->
                        val matches = mutableListOf<String>()
                        for (file in workspaces.walk(root)) {
                            if (file.length() > WorkspaceRepository.MAX_FILE) continue
                            val text = runCatching { workspaces.read(root, file.relativeTo(root).invariantSeparatorsPath).content }.getOrNull() ?: continue
                            text.lineSequence().forEachIndexed { index, line -> if (line.contains(query, true) && matches.size < 80) matches += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.take(500)}" }
                            if (matches.size >= 80) break
                        }
                        matches.joinToString("\n").ifBlank { "No matches." }
                    })
                }
                "git_status" -> { val status = git.snapshot(workspaceId); ToolResult("Branch: ${status.branch}\n" + status.files.joinToString("\n") { "${if (it.staged) "staged" else "worktree"} ${it.status}: ${it.path}" }.ifBlank { "Clean workspace" }) }
                "git_diff" -> ToolResult(git.diff(workspaceId, path(), args["staged"]?.jsonPrimitive?.booleanOrNull ?: false).take(24_000))
                in WRITES -> {
                    // Validate before asking, then revalidate against disk at the actual mutation.
                    val expected = preview(workspaceId, call)
                    var checkpoint = ""
                    workspaces.transaction(workspaceId) { root ->
                        val old = runCatching { workspaces.read(root, expected.path).content }.getOrDefault("")
                        if (old != expected.before) throw WorkspaceException("File changed since approval. Request the edit again.")
                        if (call.name == "replace_text") {
                            val oldText = args.getValue("old_text").jsonPrimitive.content
                            require(oldText.isNotEmpty() && old.split(oldText).size == 2) { "old_text must occur exactly once." }
                        }
                        checkpoint = checkpoints.captureLocked(root, workspaceId, "AI · ${call.name} · ${expected.path}")
                        workspaces.write(root, expected.path, expected.after, if (old.isEmpty() && !workspaces.resolve(root, expected.path).exists()) null else WorkspaceRepository.sha(old.toByteArray()))
                    }
                    ToolResult("Updated ${expected.path}. Checkpoint: $checkpoint", checkpointId = checkpoint)
                }
                else -> ToolResult("Unsupported tool", true)
            }.let { if (it.content.length > 24_000) it.copy(content = it.content.take(24_000) + "\n[Output truncated at 24,000 characters]") else it }
        } catch (e: Exception) { ToolResult((e.message ?: "Tool failed").take(2000), true) }
    }
}
