package dev.conduit.ide.vcs

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.util.EnvironmentUtil
import dev.conduit.agents.acp.process.ExecutableResolver
import dev.conduit.ide.ConduitBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.charset.StandardCharsets

/**
 * The user's GitHub CLI, with the user's own login. Used to list and read pull requests, and,
 * only after the user confirmed in the IDE, to create a pull request or post a review comment.
 */
object GhCli {
    private const val TIMEOUT_MS = 60_000

    fun executable(): String? = ExecutableResolver(EnvironmentUtil.getEnvironmentMap()).resolve("gh")

    suspend fun run(
        dir: String,
        args: List<String>,
        input: String? = null,
    ): CommandResult {
        val executable = executable() ?: throw GitException(ConduitBundle.message("review.error.noGh"))
        val command = ExecutableResolver(EnvironmentUtil.getEnvironmentMap()).commandLine(executable, args)
        return runInterruptible(Dispatchers.IO) {
            val handler =
                CapturingProcessHandler(
                    GeneralCommandLine(command)
                        .withWorkDirectory(dir)
                        .withCharset(StandardCharsets.UTF_8)
                        .withEnvironment(EnvironmentUtil.getEnvironmentMap())
                        .withEnvironment("GH_PROMPT_DISABLED", "1"),
                )
            if (input != null) handler.processInput.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
            val output = handler.runProcess(TIMEOUT_MS)
            CommandResult(if (output.isTimeout) -1 else output.exitCode, output.stdout, output.stderr)
        }
    }

    /**
     * Creates a pull request for the current branch. The branch must already be pushed: Conduit
     * never pushes.
     *
     * @return the pull request's URL
     * @throws GitException when the branch is not pushed or `gh` refuses
     */
    suspend fun createPullRequest(
        dir: String,
        title: String,
        body: String,
        base: String,
    ): String {
        val upstream = GitCli.run(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if (!upstream.ok) throw GitException(ConduitBundle.message("action.pr.notPushed"))
        val ahead =
            GitCli
                .run(dir, "rev-list", "--count", "@{u}..HEAD")
                .stdout
                .trim()
                .toIntOrNull() ?: 0
        if (ahead > 0) throw GitException(ConduitBundle.message("action.pr.unpushed", ahead))
        val result = run(dir, listOf("pr", "create", "--title", title, "--body-file", "-", "--base", base), body)
        if (!result.ok) throw GitException(result.stderr.trim().ifEmpty { ConduitBundle.message("action.pr.failed") })
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("http") } ?: result.stdout.trim()
    }
}
