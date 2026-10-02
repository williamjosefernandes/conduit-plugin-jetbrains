package dev.conduit.ide.tasks

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.EnvironmentUtil
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.changes.TrackedChange
import dev.conduit.mcp.ide.tools.Analysis
import dev.conduit.mcp.ide.tools.Runner
import dev.conduit.mcp.ide.tools.TestResultsCollector
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.charset.StandardCharsets

/**
 * Checks a task's result with the IDE, never with the agent's word: the IDE's own analysis of
 * the changed files, and the project's tests, run as an IDE run configuration (results read
 * from the test runner) or, in a worktree, with the build tool (the exit code decides).
 */
class TaskVerifier(
    private val project: Project,
) {
    /**
     * @param mayRunTests asks the policy, or the user, whether tests may run now
     * @param activity reports what is being done, for the task's status
     */
    suspend fun verify(
        task: AgentTask,
        changes: List<TrackedChange>,
        mayRunTests: suspend () -> Boolean,
        activity: (String) -> Unit,
    ): Verification {
        val notes = ArrayList<String>()
        notes += ConduitBundle.message("task.verify.files", changes.size)
        val problems = if (task.worktreePath == null) countErrors(changes) else null
        notes += problems?.let { ConduitBundle.message("task.verify.problems", it) } ?: ConduitBundle.message("task.verify.noAnalysis")

        var verification = Verification(problems = problems ?: 0)
        val allowed = mayRunTests()
        if (allowed) {
            activity(ConduitBundle.message("task.activity.tests"))
            verification = runTests(task, verification)
        } else {
            notes += ConduitBundle.message("task.verify.testsSkipped")
        }
        val source = verification.testSource
        val tests =
            when {
                source == null -> ConduitBundle.message("task.verify.noTests").takeIf { allowed }
                verification.testsPassed == true -> ConduitBundle.message("task.verify.testsPassed", source)
                verification.testsPassed == false -> ConduitBundle.message("task.verify.testsFailed", source, verification.testsFailed)
                else -> ConduitBundle.message("task.verify.testsUnknown", source)
            }
        tests?.let { notes += it }
        return verification.copy(notes = notes)
    }

    private suspend fun runTests(
        task: AgentTask,
        base: Verification,
    ): Verification {
        if (task.worktreePath == null) {
            val configuration = readAction { testConfiguration(task.testConfiguration) }
            if (configuration != null) return runConfiguration(configuration, base)
        }
        val dir = task.worktreePath ?: project.basePath ?: return base
        val command = TestCommands.detect(dir) ?: return base
        val line = command.joinToString(" ")
        AuditLog.getInstance(project).record(AuditLog.Kind.COMMAND, "verification: $line in $dir")
        val exit =
            runInterruptible(Dispatchers.IO) {
                val handler =
                    CapturingProcessHandler(
                        GeneralCommandLine(command)
                            .withWorkDirectory(dir)
                            .withCharset(StandardCharsets.UTF_8)
                            .withEnvironment(EnvironmentUtil.getEnvironmentMap()),
                    )
                val output = handler.runProcess(TEST_TIMEOUT_MS)
                if (output.isTimeout) null else output.exitCode
            }
        val failed = exit != null && exit != 0
        return base.copy(testsPassed = exit?.let { it == 0 }, testSource = line, testsFailed = if (failed) 1 else 0)
    }

    private suspend fun runConfiguration(
        configuration: RunnerAndConfigurationSettings,
        base: Verification,
    ): Verification {
        val collector = TestResultsCollector.getInstance(project)
        val before = collector.history.value.size
        val finished = Runner.run(project, configuration, TEST_TIMEOUT_MS / MS_PER_SECOND)
        val results =
            collector.history.value
                .drop(before)
                .lastOrNull()
        val exitedCleanly = finished.exitCode == null || finished.exitCode == 0
        return if (results != null) {
            base.copy(
                testsPassed = results.failed.isEmpty() && exitedCleanly,
                testsRun = results.tests.size,
                testsFailed = results.failed.size,
                testSource = configuration.name,
            )
        } else {
            base.copy(testsPassed = finished.exitCode?.let { it == 0 }, testSource = configuration.name)
        }
    }

    /** The configuration named [name], or the first one that runs tests. Call inside a read action. */
    private fun testConfiguration(name: String?): RunnerAndConfigurationSettings? {
        val manager = RunManager.getInstance(project)
        name?.let { manager.findConfigurationByName(it) }?.let { return it }
        return manager.allSettings.firstOrNull { settings ->
            val type = settings.type.id.lowercase()
            TEST_TYPES.any { it in type } || (type.contains("gradle") && settings.name.contains("test", ignoreCase = true))
        }
    }

    private suspend fun countErrors(changes: List<TrackedChange>): Int {
        val files =
            changes.filter { it.after != null }.mapNotNull {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(
                    it.absolutePath,
                )
            }
        if (files.isEmpty()) return 0
        return Analysis.highlights(project, files, HighlightSeverity.ERROR).values.sumOf { it.size }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15 * 60 * 1000
        const val MS_PER_SECOND = 1000
        val TEST_TYPES =
            listOf("junit", "testng", "jest", "mocha", "vitest", "karma", "pytest", "py.test", "unittest", "phpunit", "cucumber", "kotest")
    }
}
