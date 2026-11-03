package com.aicode.glass.terminal

data class ShellToken(val value: String, val operator: Boolean = false)
data class Redirection(val target: String, val stream: Int = 1, val append: Boolean = false, val merge: Boolean = false)
data class ShellCommand(val words: List<String>, val redirects: List<Redirection> = emptyList())
data class ShellPipeline(val commands: List<ShellCommand>, val next: String = "")
data class CommandOutput(val stdout: String = "", val stderr: String = "", val exitCode: Int = 0)

/** Small explicit shell grammar. Never evaluates host executables or expands environment secrets. */
object ShellParser {
    private val operators = setOf("&&", "||", ";", "|", ">>", "2>&1", "1>", "2>", ">")
    fun tokenize(input: String): List<ShellToken> {
        require(input.length <= 8192) { "Command is too long." }
        val result = mutableListOf<ShellToken>(); val word = StringBuilder(); var quote: Char? = null; var started = false
        fun flush() { if (started) { result += ShellToken(word.toString()); word.clear(); started = false } }
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null -> if (c == quote) quote = null else if (c == '\\' && quote == '"' && i + 1 < input.length && input[i + 1] in listOf('"', '\\', '$', '`')) word.append(input[++i]) else word.append(c)
                c == '\'' || c == '"' -> { quote = c; started = true }
                c == '\\' && i + 1 < input.length -> { word.append(input[++i]); started = true }
                c == '\n' || c == '\r' -> { flush(); result += ShellToken(";", true) }
                c.isWhitespace() -> flush()
                c == '#' && !started -> return result.also { if (quote != null) throw IllegalArgumentException("Unclosed quote") }
 c in listOf('|', '&', ';', '>', '1', '2') -> {
                    if((c == '1' || c == '2') && input.getOrNull(i + 1) != '>') { word.append(c); started = true; i++; continue }
                    flush(); val pair = input.substring(i, minOf(i + 2, input.length))
                    val op = when { input.startsWith("2>&1", i) -> "2>&1"; pair in operators -> pair; c == '&' -> throw IllegalArgumentException("Background & is not supported"); c == '>' && input.getOrNull(i + 1) == '&' && input.getOrNull(i + 2) == '1' -> "2>&1"; else -> c.toString() }
                    result += ShellToken(op, true); i += op.length - 1
                }
                c in listOf('$', '`', '<') -> throw IllegalArgumentException("Host expansion, substitution and input redirection are not supported.")
                else -> { word.append(c); started = true }
            }
            i++
        }
        require(quote == null) { "Unclosed quote" }; flush(); return result
    }
    fun parse(input: String): List<ShellPipeline> {
        val tokens = tokenize(input); val pipelines = mutableListOf<ShellPipeline>(); var commands = mutableListOf<ShellCommand>(); var words = mutableListOf<String>(); var connector = ""
        fun finishCommand() { if(words.isNotEmpty()) { commands += ShellCommand(words.toList()); words = mutableListOf() } }
        fun finishPipeline(next: String) {
            finishCommand(); require(commands.isNotEmpty()) { "Empty pipeline" }
            pipelines += ShellPipeline(commands.toList(), next); commands = mutableListOf()
        }
        tokens.forEach label@{ token -> if(!token.operator) words += token.value else when(token.value) {
            "|" -> { require(words.isNotEmpty()) { "Empty pipe stage" }; finishCommand() }
            "&&", "||", ";" -> { if(words.isEmpty() && commands.isEmpty() && token.value == ";") return@label; finishPipeline(token.value) }
            else -> words += token.value
        } }
        if(words.isNotEmpty() || commands.isNotEmpty()) finishPipeline("")
        require(pipelines.size in 1..30) { "Submit one to thirty statements." }
        return pipelines.map { pipeline -> pipeline.copy(commands = pipeline.commands.map { command -> val raw = command.words
            val w = mutableListOf<String>(); val redirects = mutableListOf<Redirection>(); var i = 0
            while(i < raw.size) { val arg = raw[i]
                if(arg in listOf(">", ">>", "1>", "2>")) { require(i + 1 < raw.size) { "Missing redirect target" }; redirects += Redirection(raw[++i], if(arg.startsWith("2")) 2 else 1, arg == ">>") }
                else if(arg == "2>&1") redirects += Redirection("", 2, merge = true)
                else w += arg
                i++
            }
            require(w.isNotEmpty()) { "Missing command" }; ShellCommand(w, redirects)
        }).also { require(it.commands.size <= 6) { "At most six pipeline stages." } } }
    }
    fun isMutating(pipeline: ShellPipeline): Boolean = pipeline.commands.any { command ->
        val name = command.words.first()
        name in setOf("mkdir", "touch", "rm", "cp", "mv", "tee", "truncate", "sed") || command.redirects.any { !it.merge } ||
            (name == "git" && command.words.getOrNull(1) in setOf("init", "add", "reset", "commit", "checkout", "switch", "branch"))
    }
}
