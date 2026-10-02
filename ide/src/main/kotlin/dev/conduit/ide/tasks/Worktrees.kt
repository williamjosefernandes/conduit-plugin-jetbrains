package dev.conduit.ide.tasks

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import dev.conduit.core.FileChange
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.changes.TrackedChange
import dev.conduit.ide.vcs.GitCli
import dev.conduit.ide.vcs.GitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

/**
 * An isolated checkout a task works in.
 *
 * @property path the worktree's root
 * @property cwd where the agent runs: the project's directory inside the worktree
 * @property base the commit the worktree started from
 */
data class Worktree(
    val path: String,
    val cwd: String,
    val branch: String,
    val base: String,
)

/**
 * Git worktrees for tasks: each task gets its own checkout and branch, outside the project, so
 * agents can work in parallel without touching the user's files. Created from the last commit;
 * uncommitted changes of the project are not in it.
 */
object Worktrees {
    private val unsafe = Regex("[^a-z0-9]+")
    private const val SLUG_CHARS = 32

    /** @throws GitException if the project is not in a git repository or git refuses */
    suspend fun create(
        project: Project,
        taskId: String,
        title: String,
    ): Worktree {
        val projectDir = project.basePath ?: throw GitException(ConduitBundle.message("error.NO_PROJECT_DIR"))
        if (!GitCli.isRepository(projectDir)) throw GitException(ConduitBundle.message("error.worktree.noGit"))
        val root = GitCli.root(projectDir).replace('\\', '/')
        val base = GitCli.output(projectDir, "rev-parse", "HEAD").trim()
        val slug =
            title
                .lowercase()
                .replace(unsafe, "-")
                .trim('-')
                .take(SLUG_CHARS)
                .trim('-')
                .ifEmpty { "task" }
        val suffix = taskId.takeLast(SUFFIX_CHARS)
        val branch = "conduit/$slug-$suffix"
        val dir =
            Path
                .of(PathManager.getSystemPath(), "conduit", "worktrees", project.getProjectCacheFileName(), "$slug-$suffix")
                .toString()
                .replace('\\', '/')
        File(dir).parentFile.mkdirs()
        GitCli.output(root, "worktree", "add", "-b", branch, dir, base)
        val relative =
            File(projectDir)
                .canonicalFile
                .relativeTo(File(root).canonicalFile)
                .path
                .replace('\\', '/')
        val cwd = if (relative.isEmpty()) dir else "$dir/$relative"
        return Worktree(dir, cwd, branch, base)
    }

    /** Removes the worktree and its branch. Changes not applied to the project are lost. */
    suspend fun remove(
        project: Project,
        path: String,
        branch: String?,
    ) {
        val projectDir = project.basePath ?: return
        val root = runCatching { GitCli.root(projectDir) }.getOrNull() ?: return
        GitCli.run(root, "worktree", "remove", "--force", path)
        GitCli.run(root, "worktree", "prune")
        if (branch != null) GitCli.run(root, "branch", "-D", branch)
    }

    /** What changed in the worktree [dir] since [base], tracked and untracked files alike. */
    suspend fun changes(
        dir: String,
        base: String,
    ): List<TrackedChange> {
        val names =
            (
                GitCli.run(dir, "diff", "--name-only", "--relative", "-z", base).stdout.split('\u0000') +
                    GitCli.run(dir, "ls-files", "--others", "--exclude-standard", "-z").stdout.split('\u0000')
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return names.mapNotNull { path ->
            val before = GitCli.show(dir, base, path)?.replace("\r\n", "\n")
            val file = java.io.File(dir, path)
            val after = withContext(Dispatchers.IO) { if (file.isFile) file.readText().replace("\r\n", "\n") else null }
            dev.conduit.core.FileChange
                .between(path, before, after)
                ?.let { TrackedChange(path, "$dir/$path", before, after, it) }
        }
    }

    private const val SUFFIX_CHARS = 6
}

/** Finds how a project's tests are run from the command line, judging from its build files. */
object TestCommands {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** A build tool, recognised by a file it leaves in the project root. */
    private class Tool(
        val marker: String,
        val command: () -> List<String>,
        val applies: (File) -> Boolean = { true },
    )

    private val tools =
        listOf(
            Tool("gradlew", { if (windows) listOf("cmd.exe", "/c", "gradlew.bat", "test") else listOf("./gradlew", "test") }),
            Tool("gradlew.bat", { listOf("cmd.exe", "/c", "gradlew.bat", "test") }),
            Tool("mvnw", { if (windows) listOf("cmd.exe", "/c", "mvnw.cmd", "test") else listOf("./mvnw", "test") }),
            Tool("pom.xml", { shell("mvn", "test") }),
            Tool("build.gradle", { shell("gradle", "test") }),
            Tool("build.gradle.kts", { shell("gradle", "test") }),
            Tool("package.json", { shell("npm", "test") }) { it.readText().contains("\"test\"") },
            Tool("manage.py", { listOf(if (windows) "python" else "python3", "manage.py", "test") }),
            Tool("pytest.ini", { shell("pytest") }),
            Tool("pyproject.toml", { shell("pytest") }),
            Tool("artisan", { listOf("php", "artisan", "test") }),
            Tool("Cargo.toml", { shell("cargo", "test") }),
            Tool("go.mod", { shell("go", "test", "./...") }),
        )

    /** The command line, or null when no known build tool is there. */
    fun detect(dir: String): List<String>? =
        tools.firstOrNull { tool -> File(dir, tool.marker).let { it.isFile && tool.applies(it) } }?.command?.invoke()

    private fun shell(vararg command: String): List<String> = if (windows) listOf("cmd.exe", "/c") + command else command.toList()
}
