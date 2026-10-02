package dev.conduit.ui.actions

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.EnvironmentUtil
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentMode
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.policy.ActionRequest
import dev.conduit.policy.Outcome
import dev.conduit.policy.PolicyEngine
import java.nio.charset.StandardCharsets

/**
 * Runs a shell command the user typed, in the project directory, in the Run tool window. The
 * shell policy applies: denied commands are refused, others are confirmed unless allowlisted.
 */
object CommandRunner {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Call on the EDT. */
    fun run(
        project: Project,
        command: String,
    ) {
        val line = command.trim()
        if (line.isEmpty()) return
        val settings = ConduitSettings.getInstance()
        val policy = PolicyEngine(settings.policyRules(), { AgentMode.AGENT }, { settings.state.autonomy })
        val verdict = policy.evaluate(ActionRequest(ActionKind.SHELL_COMMAND, command = line))
        val dir = project.basePath ?: return
        when (verdict.outcome) {
            Outcome.DENY -> {
                Notifications.error(project, verdict.reason ?: ConduitBundle.message("command.denied"))
                return
            }
            Outcome.ASK, Outcome.REVIEW_DIFF -> {
                val answer =
                    Messages.showYesNoDialog(
                        project,
                        ConduitBundle.message("command.confirm", line, dir),
                        ConduitBundle.message("command.title"),
                        Messages.getQuestionIcon(),
                    )
                if (answer != Messages.YES) return
            }
            Outcome.ALLOW -> Unit
        }
        val shell = if (windows) listOf("cmd.exe", "/c", line) else listOf(System.getenv("SHELL") ?: "/bin/sh", "-c", line)
        val commandLine =
            GeneralCommandLine(shell)
                .withWorkDirectory(dir)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(EnvironmentUtil.getEnvironmentMap())
        try {
            val handler = OSProcessHandler(commandLine)
            ProcessTerminatedListener.attach(handler)
            AuditLog.getInstance(project).record(AuditLog.Kind.COMMAND, "user ran: $line")
            RunContentExecutor(project, handler).withTitle("Conduit: $line").withActivateToolWindow(true).run()
        } catch (e: com.intellij.execution.ExecutionException) {
            Notifications.error(project, e.message ?: ConduitBundle.message("command.failed"))
        }
    }
}
