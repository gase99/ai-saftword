package com.aicode.glass.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class TerminalLine(val text: String, val kind: Kind = Kind.OUTPUT) { enum class Kind { INPUT, OUTPUT, ERROR, SUCCESS } }
data class ShellToken(val value: String, val operator: Boolean = false)

/** A real, sandboxed file shell, but intentionally not a PTY, Linux ABI or container. */
class TerminalSession(val root: File) {
    private val mutex = Mutex()
    private val _lines = MutableStateFlow(listOf(TerminalLine("AiCode Glass workspace shell\nType help for commands. This is a simulation, not Linux.", TerminalLine.Kind.SUCCESS)))
    val lines = _lines.asStateFlow()
    private val _cwd = MutableStateFlow(""); val cwd = _cwd.asStateFlow()
    private val _running = MutableStateFlow(false); val running = _running.asStateFlow()
    private val history = mutableListOf<String>()
    fun clear() { _lines.value = emptyList() }

    suspend fun execute(command: String, beforeMutation: suspend (String) -> Unit = {}, git: suspend (List<String>) -> String = { "Git is available in the Git tab." }) {
        if (command.isBlank() || command.length > 8192) return
        mutex.withLock {
            _running.value = true
            val prompt = if (_cwd.value.isEmpty()) "~" else "~/" + _cwd.value
            append(prompt + " $ " + command, TerminalLine.Kind.INPUT)
            history.add(command); if (history.size > 100) history.removeAt(0)
            try {
                withTimeout(15_000) { withContext(Dispatchers.IO) {
                    var checkpointTaken = false
                    command.split('\n').filter { it.isNotBlank() }.take(30).forEach { line ->
                        val tokens = tokenize(line)
                        if (tokens.isEmpty()) return@forEach
                        val mutating = tokens.first().value in setOf("mkdir", "touch", "rm", "cp", "mv") || tokens.any { it.value == ">" || it.value == ">>" }
                        if (mutating && !checkpointTaken) { withContext(Dispatchers.Default) { beforeMutation(command.take(80)) }; checkpointTaken = true }
                        val pipelines = mutableListOf<MutableList<String>>()
                        var part = mutableListOf<String>(); var redirect: String? = null; var append = false
                        var i = 0
                        while (i < tokens.size) {
                            val token = tokens[i]
                            if (token.operator && token.value in listOf("|", ">", ">>")) {
                                if (token.value == "|") { pipelines += part; part = mutableListOf() }
                                else {
                                    redirect = tokens.getOrNull(++i)?.takeIf { !it.operator }?.value ?: throw IllegalArgumentException("Missing redirect target")
                                    append = token.value == ">>"
                                }
                            } else { if (token.operator) throw IllegalArgumentException("Unsupported operator: ${token.value}"); part += token.value }
                            i++
                        }
                        pipelines += part
                        if (pipelines.size > 6) throw IllegalArgumentException("At most six pipeline stages are supported.")
                        var input = ""
                        pipelines.forEach { args -> ensureActive(); input = runCommand(args, input, git) }
                        if (redirect != null) {
                            val target = resolve(redirect!!)
                            if (append && target.length() + input.toByteArray().size > MAX_FILE) throw IllegalArgumentException("File limit exceeded")
                            if (!append && input.toByteArray().size > MAX_FILE) throw IllegalArgumentException("File limit exceeded")
                            target.parentFile?.mkdirs(); target.appendText(if (append && target.exists()) "\n$input" else input)
                        } else if (input.isNotBlank()) append(input.trimEnd('\n'))
                    }
                } }
            } catch (e: CancellationException) { append("Command cancelled or timed out.", TerminalLine.Kind.ERROR); throw e }
            catch (e: Exception) { append(e.message ?: "Command failed", TerminalLine.Kind.ERROR) }
            finally { _running.value = false }
        }
    }
    private fun append(text: String, kind: TerminalLine.Kind = TerminalLine.Kind.OUTPUT) {
        val additions = text.split('\n').take(1500).map { TerminalLine(it.take(4000), kind) }
        _lines.value = (_lines.value + additions).takeLast(600)
    }
    private fun resolve(path: String, allowRoot: Boolean = false): File {
        val base = root.canonicalFile.toPath()
        val candidate = if (path.startsWith("/")) base.resolve(path.removePrefix("/")) else base.resolve(_cwd.value).resolve(path)
        val normalized = candidate.normalize()
        if (!normalized.startsWith(base)) throw IllegalArgumentException("Path escapes workspace: $path")
        var current = base
        base.relativize(normalized).forEach { current = current.resolve(it); if (Files.isSymbolicLink(current)) throw IllegalArgumentException("Symbolic links are not supported.") }
        if (!allowRoot && normalized == base && path !in listOf(".", "", "~")) {
            // Directory operations on root are allowed for listing, never removal.
        }
        return normalized.toFile()
    }
    private fun relative(file: File) = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
    private fun read(file: File): String {
        if (!file.isFile) throw IllegalArgumentException("Not a file: ${relative(file)}")
        if (file.length() > MAX_FILE) throw IllegalArgumentException("File exceeds 1 MB")
        return file.readText()
    }
    private suspend fun runCommand(args: List<String>, stdin: String, git: suspend (List<String>) -> String): String {
        if (args.isEmpty()) return stdin
        val command = args.first(); val options = args.drop(1).filter { it.startsWith("-") }; val operands = args.drop(1).filterNot { it.startsWith("-") }
        fun target(index: Int = 0) = resolve(operands.getOrNull(index) ?: ".")
        return when (command) {
            "help" -> "WORKSPACE SIMULATION — no external processes\npwd · ls [-l,-a] · cd · cat · head [-n N] · tail [-n N]\nmkdir · touch · cp · mv · rm [-r] · echo · grep [-i,-n]\nfind [path] [text] · wc · tree · clear · history · date · whoami · uname\ngit status|branch|log|diff (read-only)\nPipes | and redirects > >>; single/double quotes.\nNo shell expansion, scripts, npm, Python, APK builds or package installation.\nUse GitHub Actions for Android builds. Mutations create checkpoints."
            "pwd" -> "/" + _cwd.value
            "ls" -> {
 val dir = target(); if (!dir.isDirectory) throw IllegalArgumentException("Not a directory")
                val entries = (dir.listFiles() ?: emptyArray()).filter { it.name != ".git" && (it.name !in listOf(".gradle", "build") || "-a" in options) && (!it.name.startsWith(".") || "-a" in options) }.sortedBy { it.name }
                if ("-l" in options) entries.joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.length().toString().padStart(8)} ${it.name}${if (it.isDirectory) "/" else ""}" }
                else entries.joinToString("  ") { it.name + if (it.isDirectory) "/" else "" }
            }
            "cd" -> { val path = operands.firstOrNull() ?: ""; val file = if (path == "~") root else resolve(path); if (!file.isDirectory) throw IllegalArgumentException("Not a directory: $path"); _cwd.value = relative(file).removePrefix("."); "" }
            "cat" -> if (operands.isEmpty()) stdin else operands.joinToString("\n") { read(resolve(it)) }
            "echo" -> args.drop(1).filterNot { it == "-n" }.joinToString(" ") + if ("-n" in options) "" else "\n"
            "mkdir" -> { if (operands.isEmpty()) error("mkdir requires a path"); operands.forEach { val f = resolve(it); if (f == root.canonicalFile) error("Directory already exists"); if (!f.mkdirs() && !f.isDirectory) error("Could not create $it") }; "" }
            "touch" -> { operands.forEach { val f = resolve(it); f.parentFile?.mkdirs(); if (!f.exists() && !f.createNewFile()) error("Could not create $it"); f.setLastModified(System.currentTimeMillis()) }; "" }
            "rm" -> { if (operands.isEmpty()) error("rm requires a path"); operands.forEach { val f = resolve(it); if (f == root.canonicalFile || relative(f).split('/').contains(".git")) error("Refusing to remove workspace or Git metadata"); if (f.isDirectory && "-r" !in options && "-rf" !in options) error("Directory requires -r"); if (f.exists() && !f.deleteRecursively()) error("Could not remove $it") }; "" }
            "cp" -> { if (operands.size != 2) error("cp SOURCE DEST"); val src = resolve(operands[0]); var dest = resolve(operands[1]); if (dest.isDirectory) dest = File(dest, src.name); if (dest.canonicalPath.startsWith(src.canonicalPath + File.separator)) error("Cannot copy into itself"); if (src.isDirectory) src.copyRecursively(dest, overwrite = true) else { read(src); dest.parentFile?.mkdirs(); src.copyTo(dest, overwrite = true) }; "" }
            "mv" -> { if (operands.size != 2) error("mv SOURCE DEST"); val src = resolve(operands[0]); val dest = resolve(operands[1]); if (src == root.canonicalFile || !src.exists()) error("Invalid source"); if (!src.renameTo(dest)) error("Could not move file"); "" }
            "grep" -> { if (operands.isEmpty()) error("grep PATTERN [FILE]"); val regex = Regex(operands[0], if ("-i" in options) setOf(RegexOption.IGNORE_CASE) else emptySet()); val text = if (operands.size > 1) read(resolve(operands[1])) else stdin; text.lineSequence().mapIndexedNotNull { i, line -> if (regex.containsMatchIn(line)) (if ("-n" in options) "${i + 1}:" else "") + line else null }.take(1000).joinToString("\n") }
            "head", "tail" -> { val n = args.indexOf("-n").let { if (it >= 0) args.getOrNull(it + 1)?.toIntOrNull() ?: 10 else 10 }.coerceIn(1, 1000); val text = if (operands.isNotEmpty() && operands.lastOrNull() != n.toString()) read(target(operands.lastIndex)) else stdin; val lines = text.lines(); (if (command == "head") lines.take(n) else lines.takeLast(n)).joinToString("\n") }
            "wc" -> { val text = if (operands.isEmpty()) stdin else read(target()); "${text.lines().size} ${text.split(Regex("\\s+")).count { it.isNotBlank() }} ${text.toByteArray().size}" }
            "find", "tree" -> { val dir = target(); if (!dir.isDirectory) error("Not a directory"); dir.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && (command == "tree" || operands.getOrNull(1)?.let { query -> it.name.contains(query, true) } != false) }.take(1000).joinToString("\n") { relative(it) } }
            "clear" -> { clear(); "" }
            "history" -> history.mapIndexed { i, s -> "${i + 1}  $s" }.joinToString("\n")
            "date" -> LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            "whoami" -> "developer (workspace simulation)"
            "uname" -> "AiCodeGlass workspace-shell 1.0 Android; not a Linux kernel"
            "git" -> git(operands)
            "python", "python3", "node", "npm", "sh", "bash", "apk", "apt", "./gradlew" -> throw IllegalArgumentException("Unsupported: this app does not include a Linux runtime. Use GitHub Actions to build Android projects. No command was executed.")
            else -> throw IllegalArgumentException("Unknown command: $command. Type help.")
        }
    }
    companion object {
        const val MAX_FILE = 1024L * 1024
        fun tokenize(input: String): List<ShellToken> {
            val out = mutableListOf<ShellToken>(); val token = StringBuilder(); var quote: Char? = null; var started = false; var i = 0
            fun flush() { if (started) { out += ShellToken(token.toString()); token.clear(); started = false } }
            while (i < input.length) {
                val c = input[i]
                when {
                    quote != null -> if (c == quote) quote = null else token.append(c)
                    c == '\'' || c == '"' -> { quote = c; started = true }
                    c == '\\' && i + 1 < input.length -> { token.append(input[++i]); started = true }
                    c.isWhitespace() -> flush()
                    c == '|' || c == '>' -> { flush(); val op = if (c == '>' && input.getOrNull(i + 1) == '>') { i++; ">>" } else c.toString(); out += ShellToken(op, true) }
                    c == ';' || c == '&' || c == '$' || c == '`' -> throw IllegalArgumentException("Shell expansion and command separators are not supported.")
                    else -> { token.append(c); started = true }
                }; i++
            }
            if (quote != null) throw IllegalArgumentException("Unclosed quote")
            flush(); return out
        }
    }
}
