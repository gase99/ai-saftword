package com.aicode.glass.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerminalSessionTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun tokenizerPreservesQuotesAndRedirects() {
        assertEquals(listOf("echo", "hello world", ">", "a.txt"), TerminalSession.tokenize("echo 'hello world' > a.txt").map { it.value })
        assertEquals("a\"b", TerminalSession.tokenize("echo a\\\"b").last().value)
        assertThrows(IllegalArgumentException::class.java) { TerminalSession.tokenize("echo 'unterminated") }
        assertThrows(IllegalArgumentException::class.java) { TerminalSession.tokenize("echo \$HOME") }
    }
    @Test fun fileMutationCreatesOneCheckpointPerCommand() = runBlocking {
        val root = folder.newFolder(); val shell = TerminalSession(root); var checkpoints = 0
        shell.execute("mkdir src\necho hello > src/a.txt\ncat src/a.txt", beforeMutation = { checkpoints++ })
        assertEquals(1, checkpoints); assertEquals("hello", File(root, "src/a.txt").readText().trim())
        assertEquals("hello", shell.lines.value.last().text)
    }
    @Test fun pipesAndReadOnlyCommandsWork() = runBlocking {
        val root = folder.newFolder(); File(root, "a.txt").writeText("alpha\nbeta\nALPHABET")
        val shell = TerminalSession(root)
        shell.execute("cat a.txt | grep -i alpha")
        assertEquals("alpha\nALPHABET", shell.lines.value.takeLast(2).joinToString("\n") { it.text })
        shell.execute("cd ../..")
        assertTrue(shell.lines.value.last().text.contains("escapes workspace"))
    }
    @Test fun unsupportedRuntimeIsNotPretended() = runBlocking {
        val shell = TerminalSession(folder.newFolder()); shell.execute("npm install")
        assertTrue(shell.lines.value.last().text.contains("No command was executed"))
        assertEquals(TerminalLine.Kind.ERROR, shell.lines.value.last().kind)
    }
    @Test fun readOnlyCommandsDoNotSnapshot() = runBlocking {
        val shell = TerminalSession(folder.newFolder()); var count = 0
        shell.execute("pwd\nwhoami\nhelp", beforeMutation = { count++ })
        assertEquals(0, count)
    }
    @Test fun refusesToRemoveGitMetadata() = runBlocking {
        val root = folder.newFolder(); File(root, ".git").mkdirs(); val shell = TerminalSession(root)
        shell.execute("rm -r .git")
        assertTrue(File(root, ".git").exists()); assertEquals(TerminalLine.Kind.ERROR, shell.lines.value.last().kind)
    }
    @Test fun refusesSymlinkEscape() = runBlocking {
        val root = folder.newFolder(); val outside = folder.newFolder(); File(outside, "private.txt").writeText("secret")
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val shell = TerminalSession(root); shell.execute("cat link/private.txt")
        assertTrue(shell.lines.value.last().text.contains("Symbolic links"))
    }
}
