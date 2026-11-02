package com.aicode.glass.data

import com.aicode.glass.core.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.*
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

import javax.inject.Inject
import javax.inject.Singleton

data class GitFile(val path: String, val status: String, val staged: Boolean)
data class GitCommit(val hash: String, val message: String, val author: String, val time: Long)
data class GitSnapshot(val initialized: Boolean = false, val branch: String = "", val files: List<GitFile> = emptyList(),
    val branches: List<String> = emptyList(), val commits: List<GitCommit> = emptyList(), val remote: String = "")

@Singleton
class GitRepository @Inject constructor(private val workspaces: WorkspaceRepository, private val secrets: SecretStore) {
    private suspend fun <T> operation(id: String, block: (Git) -> T): T = workspaces.transaction(id) { root ->
        withContext(Dispatchers.IO) { Git.open(root).use(block) }
    }
    suspend fun initialize(id: String) = workspaces.transaction(id) { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
    }
    suspend fun snapshot(id: String): GitSnapshot = workspaces.transaction(id) { root ->
        if (!File(root, ".git").exists()) return@transaction GitSnapshot()
        Git.open(root).use { git ->
            val status = git.status().call()
            val files = buildList {
                status.added.forEach { add(GitFile(it, "Added", true)) }
                status.changed.forEach { add(GitFile(it, "Modified", true)) }
                status.removed.forEach { add(GitFile(it, "Deleted", true)) }
                status.modified.forEach { add(GitFile(it, "Modified", false)) }
                status.missing.forEach { add(GitFile(it, "Deleted", false)) }
                status.untracked.forEach { add(GitFile(it, "Untracked", false)) }
                status.conflicting.forEach { add(GitFile(it, "Conflict", false)) }
            }.distinctBy { it.path + it.staged }.sortedBy { it.path }
            val commits = if (git.repository.resolve(Constants.HEAD) == null) emptyList() else git.log().setMaxCount(50).call().map {
                GitCommit(it.name, it.shortMessage, it.authorIdent.name, it.commitTime.toLong() * 1000)
            }
            GitSnapshot(true, git.repository.branch, files,
                git.branchList().call().map { Repository.shortenRefName(it.name) }, commits,
                git.repository.config.getString("remote", "origin", "url") ?: "")
        }
    }
    suspend fun stage(id: String, path: String, staged: Boolean = true) = operation(id) { git ->
        if (staged) git.add().addFilepattern(path).call()
        else git.reset().addPath(path).call()
        Unit
    }
    suspend fun stageAll(id: String) = operation(id) { it.add().addFilepattern(".").call(); Unit }
    suspend fun commit(id: String, message: String, name: String, email: String) = operation(id) { git ->
        require(message.isNotBlank()) { "Enter a commit message." }
        require(name.isNotBlank() && email.matches(Regex("^[^\\s@]+@[^\\s@]+$"))) { "Set a valid Git author name and email in Settings." }
        if (git.status().call().isClean) throw IllegalStateException("No changes to commit.")
        val person = PersonIdent(name.trim(), email.trim())
        git.commit().setMessage(message.trim()).setAuthor(person).setCommitter(person).setAllowEmpty(false).call().name
    }
    suspend fun createBranch(id: String, name: String) = operation(id) { git ->
        require(Repository.isValidRefName("refs/heads/$name")) { "Invalid branch name." }
        git.branchCreate().setName(name).call(); Unit
    }
    suspend fun switchBranch(id: String, name: String, checkpoints: CheckpointRepository) = workspaces.transaction(id) { root ->
        Git.open(root).use { git ->
            if (!git.status().call().isClean) throw IllegalStateException("Commit or stage your changes before switching branches. No files were changed.")
            checkpoints.captureLocked(root, id, "Before switching to $name")
            git.checkout().setName(name).call(); Unit
        }
    }
    suspend fun diff(id: String, path: String, staged: Boolean): String = operation(id) { git ->
        val out = ByteArrayOutputStream(); val repo = git.repository
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(repo); formatter.setDetectRenames(true)
            val head = repo.resolve("HEAD^{tree}")
            val old = if (staged) {
                if (head == null) EmptyTreeIterator() else CanonicalTreeParser().apply { repo.newObjectReader().use { reset(it, head) } }
            } else DirCacheIterator(repo.readDirCache())
            val new = if (staged) DirCacheIterator(repo.readDirCache()) else FileTreeIterator(repo)
            formatter.setPathFilter(PathFilterGroup.createFromStrings(path))
            formatter.format(old, new)
        }
        out.toString("UTF-8").ifBlank { "No tracked diff. New untracked files appear here after staging." }.take(200_000)
    }
    suspend fun commitDiff(id: String, hash: String): String = operation(id) { git ->
        val out = ByteArrayOutputStream()
        RevWalk(git.repository).use { walk ->
            val commit = walk.parseCommit(ObjectId.fromString(hash))
            val new = CanonicalTreeParser().apply { git.repository.newObjectReader().use { reset(it, commit.tree.id) } }
            val old = commit.parents.firstOrNull()?.let { parent ->
                val parsed = walk.parseCommit(parent.id); CanonicalTreeParser().apply { git.repository.newObjectReader().use { reset(it, parsed.tree.id) } }
            } ?: EmptyTreeIterator()
            DiffFormatter(out).use { it.setRepository(git.repository); it.setDetectRenames(true); it.format(old, new) }
        }
        out.toString("UTF-8").take(200_000)
    }
    suspend fun setRemote(id: String, url: String) = operation(id) { git ->
        validateRemote(url)
        git.repository.config.apply { setString("remote", "origin", "url", url); setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*"); save() }
    }
    suspend fun sync(id: String, action: String) = operation(id) { git ->
        val token = secrets.get("git_token")
        val provider = if (token.isBlank()) null else UsernamePasswordCredentialsProvider("x-access-token", token)
        val command: TransportCommand<*, *> = when (action) {
            "fetch" -> git.fetch().setRemote("origin").setRemoveDeletedRefs(true)
            "push" -> git.push().setRemote("origin").add(git.repository.branch)
            "pull" -> {
                if (!git.status().call().isClean) throw IllegalStateException("Commit your changes before pulling.")
                git.pull().setRemote("origin").setRemoteBranchName(git.repository.branch).setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            }
            else -> error("Unknown Git action")
        }
        command.setTimeout(60); provider?.let { command.setCredentialsProvider(it) }
        command.call(); Unit
    }
    companion object {
        fun validateRemote(url: String) {
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Use an HTTPS Git remote without embedded credentials."
            }
        }
    }
}

/** Read-only terminal bridge. The caller already owns the workspace transaction. */
fun GitRepository.terminalCommand(root: java.io.File, args: List<String>): String {
    if (!java.io.File(root, ".git").exists()) return "No Git repository. Initialize one in the Git tab."
    return Git.open(root).use { git -> when (args.firstOrNull()) {
        "status" -> "On branch ${git.repository.branch}\n" + git.status().call().let { status ->
            (status.added.map { "staged added: $it" } + status.changed.map { "staged modified: $it" } + status.modified.map { "modified: $it" } + status.untracked.map { "untracked: $it" } + status.missing.map { "missing: $it" }).joinToString("\n").ifBlank { "Working tree clean" }
        }
        "branch" -> git.branchList().call().joinToString("\n") { (if (Repository.shortenRefName(it.name) == git.repository.branch) "* " else "  ") + Repository.shortenRefName(it.name) }
        "log" -> if (git.repository.resolve(Constants.HEAD) == null) "No commits yet." else git.log().setMaxCount(20).call().joinToString("\n") { "${it.name.take(7)} ${it.shortMessage}" }
        "diff" -> {
            val out = ByteArrayOutputStream(); DiffFormatter(out).use { it.setRepository(git.repository); it.format(DirCacheIterator(git.repository.readDirCache()), FileTreeIterator(git.repository)) }; out.toString("UTF-8").take(24000).ifBlank { "No unstaged tracked changes." }
        }
        else -> "Read-only terminal Git: status, branch, log, diff. Use the Git tab for mutations and remote operations."
    } }
}
