package com.aicode.glass.terminal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerminalSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun host(root: File, checkpoints: MutableList<String> = mutableListOf()) = object : TerminalHost {
        override suspend fun checkpoint(label: String) { checkpoints += label }
        override suspend fun git(args: List<String>) = CommandOutput("git ${args.joinToString(" ")}\n")
    }
    @Test fun parserHandlesQuotedOperatorsAndSeparators() {
        val p = ShellParser.parse("echo 'a && b' | cat && false || echo recovered")
        assertEquals(3, p.size); assertEquals("a && b", p[0].commands[0].words[1]); assertEquals("&&", p[0].next)
        assertThrows(IllegalArgumentException::class.java) { ShellParser.parse("echo \$HOME") }
        assertThrows(IllegalArgumentException::class.java) { ShellParser.parse("cat < /etc/passwd") }
    }
    @Test fun realFilesAndCheckpointBeforeEveryMutation() = runBlocking {
        val root = folder.newFolder(); val checkpoints = mutableListOf<String>(); val shell = TerminalSession(root)
        shell.execute("mkdir src\necho hello > src/a.txt\ncat src/a.txt", host(root, checkpoints))
        assertEquals(2, checkpoints.size); assertEquals("hello", File(root, "src/a.txt").readText().trim())
        assertEquals("hello", shell.lines.value.last().text)
    }
    @Test fun conditionalChainsAndHistoryAreCorrect() = runBlocking {
        val shell = TerminalSession(folder.newFolder()); shell.execute("false && echo wrong || echo recovered", host(shell.root))
        assertEquals(0, shell.exitCode.value); assertEquals("recovered", shell.lines.value.last().text)
        shell.execute("true && echo yes", host(shell.root)); assertEquals("yes", shell.lines.value.last().text)
        assertEquals(2, shell.history.value.size)
    }
    @Test fun independentSessionsHaveIndependentState() = runBlocking {
        val root = folder.newFolder(); File(root, "sub").mkdir(); val a = TerminalSession(root); val b = TerminalSession(root)
        a.execute("cd sub\npwd", host(root)); assertEquals("/sub", a.lines.value.last().text)
        b.execute("pwd", host(root)); assertEquals("/", b.lines.value.last().text); assertTrue(b.history.value.size == 1)
    }
    @Test fun concurrentSessionsDoNotInterleaveMutationCheckpoints() = runBlocking {
        val root = folder.newFolder(); val lock = kotlinx.coroutines.sync.Mutex(); val checkpoints = mutableListOf<String>()
        val h = object : TerminalHost { override suspend fun checkpoint(label: String) = lock.withLock { checkpoints += label; delay(5) }; override suspend fun git(args: List<String>) = CommandOutput() }
        coroutineScope { (1..4).map { n -> launch(Dispatchers.IO) { TerminalSession(root).execute("echo $n > file$n.txt", h) } }.joinAll() }
        assertEquals(4, checkpoints.size); assertEquals(4, root.listFiles()!!.size)
    }
    @Test fun stdoutStderrAndExitStatusAreDistinct() = runBlocking {
        val shell = TerminalSession(folder.newFolder()); shell.execute("npm install", host(shell.root))
        assertEquals(127, shell.exitCode.value); assertEquals(TerminalLine.Kind.ERROR, shell.lines.value.last().kind)
        shell.execute("false || echo ok", host(shell.root)); assertEquals(0, shell.exitCode.value)
    }
    @Test fun redirectsAppendAndMergeExplicitly() = runBlocking {
        val root = folder.newFolder(); val shell = TerminalSession(root)
        shell.execute("echo one > a.txt\necho two >> a.txt\ncat a.txt", host(root))
        assertEquals("one\ntwo\n", File(root, "a.txt").readText())
        shell.execute("npm install > result.txt 2>&1", host(root)); assertTrue(File(root, "result.txt").readText().contains("UNSUPPORTED_RUNTIME"))
    }
    @Test fun pathAndGitMetadataProtection() = runBlocking {
        val root = folder.newFolder(); File(root, ".git").mkdir(); val shell = TerminalSession(root)
        shell.execute("rm -r .git", host(root)); assertTrue(File(root, ".git").exists()); assertNotEquals(0, shell.exitCode.value)
        shell.execute("cd ../..", host(root)); assertNotEquals(0, shell.exitCode.value)
        val outside = folder.newFolder(); FilesLink.create(File(root, "link"), outside)
        shell.execute("cat link/private", host(root)); assertNotEquals(0, shell.exitCode.value)
    }
    @Test fun cancellationDoesNotBlockNextSessionCommand() = runBlocking {
        val shell = TerminalSession(folder.newFolder()); val started = CompletableDeferred<Unit>()
        val h = object : TerminalHost { override suspend fun checkpoint(label: String) { started.complete(Unit); awaitCancellation() }; override suspend fun git(args: List<String>) = CommandOutput() }
        val job = launch { shell.execute("touch never.txt", h) }; started.await(); shell.cancel(); job.join()
        assertEquals(130, shell.exitCode.value); assertFalse(shell.running.value)
        shell.execute("echo ready", host(shell.root)); assertEquals("ready", shell.lines.value.last().text)
    }
    @Test fun headTailGrepAndGlobWork() = runBlocking {
        val root = folder.newFolder(); File(root, "lines.txt").writeText("one\ntwo\nthree"); File(root, "other.txt").writeText("four")
        val shell = TerminalSession(root); shell.execute("head -n 2 lines.txt", host(root)); assertEquals(listOf("one","two"), shell.lines.value.takeLast(2).map { it.text })
        shell.execute("grep -n three lines.txt", host(root)); assertEquals("3:three", shell.lines.value.last().text)
        shell.execute("cat *.txt", host(root)); assertTrue(shell.lines.value.joinToString("\n") { it.text }.contains("four"))
    }
}
private object FilesLink { fun create(link: File, target: File) { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()) } }
