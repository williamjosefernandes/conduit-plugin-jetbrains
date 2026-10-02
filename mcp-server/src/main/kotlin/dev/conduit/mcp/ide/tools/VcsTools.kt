package dev.conduit.mcp.ide.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import dev.conduit.core.ActionKind
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.Page
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.authorize
import dev.conduit.mcp.ide.checkReadable
import dev.conduit.mcp.ide.toolSchema
import dev.conduit.policy.ActionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets

internal abstract class VcsTool(
    override val name: String,
    override val description: String,
    override val risk: Risk = Risk.READ,
) : IdeTool {
    override val domain = ToolDomain.VCS
}

/** The user's `git`, run in the project directory. Nothing here pushes. */
internal object Git {
    private const val TIMEOUT_MS = 60_000
    private const val MAX_OUTPUT = 40_000

    suspend fun run(
        project: Project,
        args: List<String>,
    ): String {
        val dir = project.basePath ?: throw ToolException("The project has no directory")
        val command =
            GeneralCommandLine(listOf("git") + args)
                .withWorkDirectory(dir)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(EnvironmentUtil.getEnvironmentMap())
                .withEnvironment("GIT_TERMINAL_PROMPT", "0")
        val output =
            runInterruptible(Dispatchers.IO) {
                try {
                    CapturingProcessHandler(command).runProcess(TIMEOUT_MS)
                } catch (e: com.intellij.execution.ExecutionException) {
                    throw ToolException("git could not be started: ${e.message}", e)
                }
            }
        if (output.isTimeout) throw ToolException("git ${args.firstOrNull().orEmpty()} timed out")
        if (output.exitCode != 0) throw ToolException(output.stderr.trim().ifEmpty { "git ${args.firstOrNull().orEmpty()} failed" })
        val text = output.stdout
        return if (text.length > MAX_OUTPUT) text.take(MAX_OUTPUT) + "\n… (truncated)" else text
    }
}

internal object GitStatusTool : VcsTool(
    "git_status",
    "Shows the current branch, how far it is from its upstream, and the changed, staged and untracked files.",
) {
    override val inputSchema = toolSchema { }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult = ToolResult(Git.run(project, listOf("status", "--short", "--branch")).trim().ifEmpty { "Nothing to report." })
}

internal object GitDiffTool : VcsTool(
    "git_diff",
    "Shows uncommitted changes as a unified diff, for one file or the whole project. Set 'staged' for what is staged, " +
        "or 'base' (a branch or commit) to compare the current branch with it.",
) {
    override val inputSchema =
        toolSchema {
            string("file", "Only this file (project-relative).")
            boolean("staged", "Show the staged changes instead of the working tree.")
            string("base", "Compare HEAD with this branch or commit instead, e.g. main.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val file = arguments.stringOrNull("file")?.also { ctx.checkReadable(it) }
        val command = ArrayList<String>()
        command += "diff"
        arguments.stringOrNull("base")?.let { command += "$it...HEAD" }
        if (arguments.bool("staged", false)) command += "--cached"
        if (file != null) command += listOf("--", file)
        val diff = Git.run(project, command)
        return ToolResult(diff.ifBlank { "No changes." })
    }
}

internal object GitLogTool : VcsTool(
    "git_log",
    "Lists recent commits (hash, date, author, subject), optionally only those touching a file.",
) {
    override val inputSchema =
        toolSchema {
            string("file", "Only commits that changed this file.")
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val file = arguments.stringOrNull("file")?.also { ctx.checkReadable(it) }
        val command = mutableListOf("log", "-200", "--date=short", "--format=%h %ad %an  %s")
        if (file != null) command += listOf("--follow", "--", file)
        val lines = Git.run(project, command).lines().filter { it.isNotBlank() }
        return Page.from(arguments).render(lines, "No commits.")
    }
}

internal object GitBlameTool : VcsTool(
    "git_blame",
    "Tells who last changed each line of a file region and in which commit, to understand why code is the way it is.",
) {
    override val inputSchema =
        toolSchema {
            string("file", "Project-relative path.", required = true)
            integer("from_line", "First line (1-based).")
            integer("to_line", "Last line.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val file = arguments.string("file")
        ctx.checkReadable(file)
        val command = mutableListOf("blame", "--date=short")
        val from = arguments.intOrNull("from_line")
        if (from != null) command += listOf("-L", "$from,${arguments.intOrNull("to_line") ?: from}")
        command += listOf("--", file)
        return ToolResult(Git.run(project, command).trimEnd())
    }
}

internal object GitBranchesTool : VcsTool(
    "git_branches",
    "Lists local branches, marking the current one.",
) {
    override val inputSchema = toolSchema { }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult = ToolResult(Git.run(project, listOf("branch", "--list")).trimEnd())
}

internal object GitCreateBranchTool : VcsTool(
    "git_create_branch",
    "Creates a branch from the current commit and switches to it; uncommitted changes come along. The user may be asked.",
    Risk.EXECUTE,
) {
    private val valid = Regex("^[A-Za-z0-9._/-]+$")

    override val inputSchema = toolSchema { string("name", "Branch name, e.g. feature/user-validation.", required = true) }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val name = ToolArgs(args).string("name")
        if (!valid.matches(name) || name.startsWith("-")) throw ToolException("'$name' is not a valid branch name")
        ctx.authorize(ActionRequest(ActionKind.OTHER, command = "git switch -c $name"), "Create and switch to branch '$name'")
        Git.run(project, listOf("switch", "-c", name))
        return ToolResult("Switched to the new branch '$name'.")
    }
}

internal object GitCommitTool : VcsTool(
    "git_commit",
    "Commits the given files (or everything already staged) with a message. Always subject to the commit policy; it " +
        "never pushes.",
    Risk.EXECUTE,
) {
    override val inputSchema =
        toolSchema {
            string("message", "The commit message.", required = true)
            stringArray("files", "Files to stage and commit; omit to commit what is staged.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val message = arguments.string("message")
        val files = arguments.strings("files")
        files.forEach { ctx.checkReadable(it) }
        val summary = message.lineSequence().first().take(SUMMARY_CHARS)
        ctx.authorize(ActionRequest(ActionKind.GIT_COMMIT), "Commit \"$summary\"" + if (files.isEmpty()) "" else " (${files.size} files)")
        if (files.isNotEmpty()) Git.run(project, listOf("add", "--") + files)
        Git.run(project, listOf("commit", "-m", message))
        return ToolResult(Git.run(project, listOf("log", "-1", "--format=Committed %h %s")).trim())
    }

    private const val SUMMARY_CHARS = 72
}
