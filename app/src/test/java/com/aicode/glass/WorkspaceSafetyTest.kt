package com.aicode.glass

import com.aicode.glass.data.WorkspaceRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSafetyTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun atomicWritesReplaceContents() {
        val file = File(folder.root, "a.txt"); WorkspaceRepository.atomicWrite(file, "first".toByteArray()); WorkspaceRepository.atomicWrite(file, "second".toByteArray())
        assertEquals("second", file.readText()); assertEquals(64, WorkspaceRepository.sha(byteArrayOf()).length)
    }
    @Test fun swapPreservesGitAndRemovesNewFiles() {
        val root = folder.newFolder(); File(root, ".git").mkdirs(); File(root, ".git/HEAD").writeText("ref: refs/heads/main"); File(root, "old.txt").writeText("remove")
        val staging = folder.newFolder(); File(staging, "restored.txt").writeText("safe")
        WorkspaceRepository.swapContents(root, staging)
        assertFalse(File(root, "old.txt").exists()); assertEquals("safe", File(root, "restored.txt").readText()); assertEquals("ref: refs/heads/main", File(root, ".git/HEAD").readText())
    }
}
