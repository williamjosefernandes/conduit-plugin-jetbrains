package dev.conduit.ide.vcs

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.EnvironmentUtil
import dev.conduit.agents.acp.process.ExecutableResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.charset.StandardCharsets

/** Output of a finished command. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/** Thrown when git is missing or a command fails; the message is suitable for the user. */
class GitException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Runs the user's `git`, the same binary the IDE's Git integration uses. Read-only commands are
 * used freely; commands that change the repository are only issued by explicit user actions
 * (worktrees for tasks, commits the user confirmed). Nothing here ever pushes.
 */
object GitCli {
    private val log = logger<GitCli>()
    private const val TIMEOUT_MS = 60_000

    /** Absolute path of `git` on the user's `PATH`, or null when it is not installed. */
    fun executable(): String? = ExecutableResolver(EnvironmentUtil.getEnvironmentMap()).resolve("git")

    /** Runs `git args` in [dir] and returns whatever it printed, failing or not. */
    suspend fun run(
        dir: String,
        vararg args: String,
        input: String? = null,
    ): CommandResult {
        val git = executable() ?: throw GitException("Git was not found on the PATH")
        val command =
            GeneralCommandLine(git, *args)
                .withWorkDirectory(dir)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(EnvironmentUtil.getEnvironmentMap())
                .withEnvironment("GIT_TERMINAL_PROMPT", "0")
                .withEnvironment("GIT_OPTIONAL_LOCKS", "0")
        return runInterruptible(Dispatchers.IO) {
            val handler = CapturingProcessHandler(command)
            if (input != null) {
                handler.processInput.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
            }
            val output = handler.runProcess(TIMEOUT_MS)
            if (output.isTimeout) throw GitException("git ${args.firstOrNull().orEmpty()} timed out")
            CommandResult(output.exitCode, output.stdout, output.stderr)
        }
    }

    /** Runs `git args` and returns its standard output, or throws with git's own error message. */
    suspend fun output(
        dir: String,
        vararg args: String,
    ): String {
        val result = run(dir, *args)
        if (!result.ok) {
            log.debug("git ${args.joinToString(" ")} failed: ${result.stderr}")
            throw GitException(result.stderr.trim().ifEmpty { "git ${args.firstOrNull().orEmpty()} failed (${result.exitCode})" })
        }
        return result.stdout
    }

    /** True when [dir] is inside a git working tree and git is installed. */
    suspend fun isRepository(dir: String): Boolean =
        executable() != null &&
            File(dir).exists() &&
            runCatching { run(dir, "rev-parse", "--is-inside-work-tree").stdout.trim() == "true" }.getOrDefault(false)

    /** The checked-out branch, or null when detached or not a repository. */
    suspend fun currentBranch(dir: String): String? =
        runCatching { output(dir, "rev-parse", "--abbrev-ref", "HEAD").trim() }.getOrNull()?.takeIf { it.isNotEmpty() && it != "HEAD" }

    /** The repository's main branch: `origin/HEAD`'s target, else `main` or `master` when they exist. */
    suspend fun defaultBranch(dir: String): String? {
        runCatching { output(dir, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD").trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.removePrefix("origin/") }
        return listOf("main", "master", "develop").firstOrNull { branch ->
            run(dir, "rev-parse", "--verify", "--quiet", "refs/heads/$branch").ok
        }
    }

    /** Content of [path] (relative to [dir]) at [revision], or null when the file does not exist there. */
    suspend fun show(
        dir: String,
        revision: String,
        path: String,
    ): String? {
        val result = run(dir, "show", "$revision:./$path")
        return if (result.ok) result.stdout else null
    }

    /** Root of the working tree containing [dir]. */
    suspend fun root(dir: String): String = output(dir, "rev-parse", "--show-toplevel").trim()
}
