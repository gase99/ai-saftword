package com.aicode.glass.terminal

import org.junit.Assert.*
import org.junit.Test

class ShellParserTest {
    @Test fun connectorBelongsToPrecedingPipeline() {
        val p = ShellParser.parse("false && echo no || echo yes; echo end")
        assertEquals(listOf("&&", "||", ";", ""), p.map { it.next })
    }
    @Test fun newlinesAreStatementSeparators() { assertEquals(3, ShellParser.parse("pwd\nls\ncat missing").size) }
    @Test fun quotedRedirectCharactersAreNotOperators() { val p = ShellParser.parse("echo '2>&1 | &&'"); assertEquals("2>&1 | &&", p.single().commands.single().words[1]) }
    @Test fun stderrRedirectIsRepresented() { val p = ShellParser.parse("echo hello 2> errors.txt").single().commands.single(); assertEquals(2, p.redirects.single().stream) }
    @Test fun stderrMergeIsOneOperator() { val p = ShellParser.parse("npm install > out.txt 2>&1").single().commands.single(); assertTrue(p.redirects.any { it.merge && it.stream == 2 }) }
    @Test fun mutationClassificationIncludesGitAndTee() { assertTrue(ShellParser.isMutating(ShellParser.parse("git add file").single())); assertTrue(ShellParser.isMutating(ShellParser.parse("echo x | tee file").single())); assertFalse(ShellParser.isMutating(ShellParser.parse("git status").single())) }
    @Test fun rejectsTooManyStatementsAndEmptyPipeline() { assertThrows(IllegalArgumentException::class.java) { ShellParser.parse((1..31).joinToString(";") { "pwd" }) }; assertThrows(IllegalArgumentException::class.java) { ShellParser.parse("pwd | | ls") } }
}
