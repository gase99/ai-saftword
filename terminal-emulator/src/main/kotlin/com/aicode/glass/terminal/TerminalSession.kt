package com.aicode.glass.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Resource IDs let the Android renderer localize diagnostics without coupling the JVM engine to Android. */
data class TerminalMessage(val code: String, val args: List<String> = emptyList())
data class TerminalLine(val text: String, val kind: Kind = Kind.OUTPUT, val id: Long = 0, val message: TerminalMessage? = null) {
    enum class Kind { INPUT, OUTPUT, ERROR, SUCCESS }
}
interface TerminalHost {
    suspend fun checkpoint(label: String)
    suspend fun git(args: List<String>): CommandOutput
}

/** Independent cwd/history/transcript; workspace writes are serialized by the host's workspace transaction. */
class TerminalSession(val root: File, val name: String = "Terminal 1", val id: String = UUID.randomUUID().toString()) {
    private val mutex = Mutex()
    private var sequence = 0L
    private val _lines = MutableStateFlow(listOf(TerminalLine("", TerminalLine.Kind.SUCCESS, sequence++, TerminalMessage("terminal_welcome"))))
    val lines = _lines.asStateFlow()
    private val _cwd = MutableStateFlow(""); val cwd = _cwd.asStateFlow()
    private val _running = MutableStateFlow(false); val running = _running.asStateFlow()
    private val _exitCode = MutableStateFlow(0); val exitCode = _exitCode.asStateFlow()
    private val _history = MutableStateFlow<List<String>>(emptyList()); val history = _history.asStateFlow()
    private var job: Job? = null
    fun clear() { _lines.value = emptyList() }
    fun cancel() { job?.cancel() }
    suspend fun execute(input: String, host: TerminalHost) = mutex.withLock {
        if (input.isBlank()) return@withLock
        if (input.length > 8192) { append(TerminalMessage("command_too_long"), TerminalLine.Kind.ERROR); return@withLock }
        _running.value = true; _exitCode.value = 0
        appendRaw(("~/" + _cwd.value).trimEnd('/') + " $ " + input, TerminalLine.Kind.INPUT)
        _history.value = (_history.value + input).takeLast(100)
        job = currentCoroutineContext()[Job]
        try {
            val parsed = ShellParser.parse(input)
            withTimeout(20_000) {
                var previous = 0; var previousConnector = ""
                for (pipeline in parsed) {
                    currentCoroutineContext().ensureActive()
                    if (!((previousConnector == "&&" && previous != 0) || (previousConnector == "||" && previous == 0))) previous = executePipeline(pipeline, host)
                    previousConnector = pipeline.next
                }
            }
            _exitCode.value = previousStatus
        } catch (e: CancellationException) {
            _exitCode.value = 130; append(TerminalMessage("command_cancelled"), TerminalLine.Kind.ERROR)
        } catch (e: Exception) {
            _exitCode.value = 2; appendRaw(e.message ?: "Command failed", TerminalLine.Kind.ERROR)
        } finally { _running.value = false; job = null }
    }
    private var previousStatus = 0
    private suspend fun executePipeline(pipeline: ShellPipeline, host: TerminalHost): Int {
        if (ShellParser.isMutating(pipeline)) host.checkpoint(pipeline.commands.joinToString(" | ") { it.words.joinToString(" ") }.take(100))
        var stdout = ""; var stderr = ""; var status = 0
        for (command in pipeline.commands) {
            currentCoroutineContext().ensureActive()
            val result = if (command.words.first() == "git") host.git(command.words.drop(1)) else run(command.words, stdout)
            stdout = result.stdout; stderr = result.stderr; status = result.exitCode
        }
        val redirects = pipeline.commands.last().redirects
        if (stderr.isNotBlank() && redirects.none { it.stream == 2 }) appendRaw(stderr.trimEnd('\n'), TerminalLine.Kind.ERROR)
        if (redirects.any { it.stream == 2 && it.merge }) stdout += stderr
        for (redirect in redirects.filterNot { it.merge }) {
            val target = resolve(redirect.target)
            val content = (if (redirect.stream == 2) stderr else stdout).let { if (redirect.target == "/dev/null") "" else it }
            if (redirect.target == "/dev/null") continue
            val old = if (redirect.append && target.exists()) read(target) else ""
            val combined = old + content
            require(combined.toByteArray().size <= MAX_FILE) { "File exceeds 1 MB" }
            target.parentFile?.mkdirs(); target.writeText(combined)
            if (redirect.stream == 1) stdout = "" else stderr = ""
        }
        if (stdout.isNotBlank()) appendRaw(stdout.trimEnd('\n'), TerminalLine.Kind.OUTPUT)
        val files = root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.take(4001).toList()
        require(files.size <= 4000 && files.sumOf { it.length() } <= 64L * 1024 * 1024 && files.all { it.length() <= MAX_FILE }) { "Workspace quota exceeded. Restore the pre-command checkpoint." }
        previousStatus = status; return status
    }
    private fun append(message: TerminalMessage, kind: TerminalLine.Kind) {
        _lines.update { (it + TerminalLine("", kind, sequence++, message)).takeLast(1000) }
    }
    private fun appendRaw(text: String, kind: TerminalLine.Kind) {
        val additions = text.lineSequence().take(1500).map { TerminalLine(it.take(4000), kind, sequence++) }.toList()
        _lines.update { (it + additions).takeLast(1000) }
    }
    private fun resolve(path: String): File {
        require(path.length <= 1000 && !path.contains('\u0000')) { "Invalid path" }
        val base = root.canonicalFile.toPath(); val expanded = if (path == "~") "" else path.removePrefix("~/")
        val candidate = if (expanded.startsWith("/")) base.resolve(expanded.removePrefix("/")) else base.resolve(_cwd.value).resolve(expanded)
        val normalized = candidate.normalize(); require(normalized.startsWith(base)) { "Path escapes workspace" }
        var current = base
        for (part in base.relativize(normalized)) {
            current = current.resolve(part); require(!Files.isSymbolicLink(current)) { "Symbolic links are not supported" }
            require(!part.toString().equals(".git", true)) { "Git metadata is protected; use Git commands" }
        }
        return normalized.toFile()
    }
    private fun relative(file: File) = root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
    private fun read(file: File): String {
        require(file.isFile) { "Not a file: ${file.name}" }; require(file.length() <= MAX_FILE) { "File exceeds 1 MB" }
        return file.readText()
    }
    private fun glob(pattern: String): List<String> {
        if (pattern.none { it in "*?[" }) return listOf(pattern)
        val regex = Regex(buildString { append("^"); pattern.forEach { when(it) { '*' -> append("[^/]*"); '?' -> append("[^/]"); '.' -> append("\\."); else -> append(it) } }; append("$") })
        val dir = resolve("."); return dir.listFiles().orEmpty().filter { it.name != ".git" && regex.matches(it.name) }.map { it.name }.sorted().take(500).ifEmpty { listOf(pattern) }
    }
    private fun run(args: List<String>, stdin: String): CommandOutput {
        val name = args.first(); val rest = args.drop(1); val flags = rest.filter { it.startsWith("-") }; val operands = rest.filterNot { it.startsWith("-") }.flatMap { glob(it) }
        fun number(flag: String, default: Int): Int { val i = rest.indexOf(flag); return if (i < 0) default else rest.getOrNull(i + 1)?.toIntOrNull()?.coerceIn(1, 1000) ?: throw IllegalArgumentException("Invalid numeric option") }
        fun file(index: Int = 0) = resolve(operands.getOrNull(index) ?: ".")
        fun ok(text: String = "") = CommandOutput(stdout = text)
        return when(name) {
            "help" -> CommandOutput(stderr = "HELP", exitCode = 0)
            "pwd" -> ok("/" + _cwd.value + "\n")
            "ls" -> { val dir = file(); require(dir.isDirectory) { "Not a directory" }; val entries = dir.listFiles().orEmpty().filter { it.name != ".git" && ("-a" in flags || !it.name.startsWith('.')) }.sortedBy { it.name }; ok(entries.joinToString("\n") { if ("-l" in flags) "${if(it.isDirectory) "d" else "-"} ${it.length().toString().padStart(8)} ${it.name}" else it.name + if(it.isDirectory) "/" else "" } + "\n") }
            "cd" -> { val f = resolve(operands.firstOrNull() ?: "~"); require(f.isDirectory) { "Not a directory" }; _cwd.value = relative(f); ok() }
            "cat" -> ok(if (operands.isEmpty()) stdin else operands.joinToString("") { read(resolve(it)) })
            "echo" -> ok(rest.filterNot { it == "-n" }.joinToString(" ") + if ("-n" in flags) "" else "\n")
            "printf" -> { val format = operands.firstOrNull() ?: ""; ok(format.replace("\\n", "\n").replace("\\t", "\t")) }
            "mkdir" -> { require(operands.isNotEmpty()) { "mkdir requires a path" }; operands.forEach { val f = resolve(it); if ("-p" !in flags) require(f.parentFile?.isDirectory == true) { "Parent does not exist; use -p" }; require(f.mkdirs() || (f.isDirectory && "-p" in flags)) { "Could not create directory" } }; ok() }
            "touch" -> { require(operands.isNotEmpty()) { "touch requires a path" }; operands.forEach { val f = resolve(it); f.parentFile?.mkdirs(); require(f.exists() || f.createNewFile()) { "Could not create file" }; f.setLastModified(System.currentTimeMillis()) }; ok() }
            "rm" -> { require(operands.isNotEmpty()) { "rm requires a path" }; operands.forEach { val f = resolve(it); require(f != root.canonicalFile) { "Cannot remove workspace root" }; require(!f.isDirectory || "-r" in flags || "-rf" in flags) { "Directory requires -r" }; require(!f.exists() || "-f" in flags || "-rf" in flags || f.deleteRecursively()) { "Could not remove file" } }; ok() }
            "cp", "mv" -> { require(operands.size >= 2) { "$name SOURCE DEST" }; val destination = resolve(operands.last()); val sources = operands.dropLast(1).map { resolve(it) }; require(sources.size == 1 || destination.isDirectory) { "Multiple sources require a directory" }; sources.forEach { source -> val dest = if(destination.isDirectory) File(destination, source.name) else destination; require(source.exists() && source != root.canonicalFile) { "Invalid source" }; require(!dest.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Cannot copy into itself" }; if(name == "mv") require(source.renameTo(dest)) { "Move failed" } else if(source.isDirectory) { require("-r" in flags) { "Directory requires -r" }; source.copyRecursively(dest, false) } else { read(source); dest.parentFile?.mkdirs(); source.copyTo(dest, false) } }; ok() }
            "grep" -> { require(operands.isNotEmpty()) { "grep PATTERN [FILE]" }; val regex = Regex(operands.first(), if("-i" in flags) setOf(RegexOption.IGNORE_CASE) else emptySet()); val numeric = rest.indexOf("-n"); val paths = rest.drop(1).filterIndexed { i, _ -> numeric < 0 || (i + 1 != numeric && i + 1 != numeric + 1) }; val text = if(paths.isEmpty()) stdin else paths.joinToString("\n") { read(resolve(it)) }; val matches = text.lineSequence().mapIndexedNotNull { i, line -> if(regex.containsMatchIn(line)) (if(numeric >= 0) "${i+1}:" else "") + line else null }.take(1000).toList(); return CommandOutput(stdout = matches.joinToString("\n").let { if(it.isEmpty()) "" else it + "\n" }, exitCode = if(matches.isEmpty()) 1 else 0) }
            "head", "tail" -> { val n = number("-n", 10); val skip = rest.indexOf("-n"); val paths = rest.filterIndexed { i, _ -> i != skip && i != skip + 1 && !rest[i].startsWith("-") }; val text = if(paths.isEmpty()) stdin else read(resolve(paths.first())); val lines = text.trimEnd('\n').lines(); ok((if(name == "head") lines.take(n) else lines.takeLast(n)).joinToString("\n") + "\n") }
            "wc" -> { val text = if(operands.isEmpty()) stdin else read(file()); ok("${text.lines().size} ${text.split(Regex("\\s+")).count { it.isNotBlank() }} ${text.toByteArray().size}\n") }
            "find", "tree" -> { val dir = file(); require(dir.isDirectory) { "Not a directory" }; val query = operands.getOrNull(1); ok(dir.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && (query == null || it.name.contains(query, true)) }.take(1000).joinToString("\n") { relative(it) } + "\n") }
            "tee" -> { val text = stdin; operands.forEach { val f = resolve(it); val combined = (if("-a" in flags && f.exists()) read(f) else "") + text; require(combined.toByteArray().size <= MAX_FILE) { "File exceeds 1 MB" }; f.parentFile?.mkdirs(); f.writeText(combined) }; ok(text) }
            "truncate" -> { val size = number("-s", 0); operands.forEach { val f = resolve(it); require(size <= MAX_FILE) { "File exceeds 1 MB" }; f.parentFile?.mkdirs(); f.writeText(" ".repeat(size)) }; ok() }
            "clear" -> { clear(); ok() }
            "history" -> ok(_history.value.mapIndexed { i, s -> "${i+1}  $s" }.joinToString("\n"))
            "date" -> ok(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            "whoami" -> ok("developer (workspace sandbox)\n")
            "uname" -> ok("AiCodeGlass workspace-shell 2.0 Android; not a Linux kernel\n")
            "true" -> ok(); "false" -> CommandOutput(exitCode = 1)
            "python", "python3", "node", "npm", "sh", "bash", "apk", "apt", "./gradlew" -> CommandOutput(stderr = "UNSUPPORTED_RUNTIME", exitCode = 127)
            else -> CommandOutput(stderr = "UNKNOWN_COMMAND: $name", exitCode = 127)
        }
    }
    companion object { const val MAX_FILE = 1024L * 1024 }
}
