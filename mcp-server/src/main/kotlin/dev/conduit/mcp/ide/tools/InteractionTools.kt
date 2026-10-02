package dev.conduit.mcp.ide.tools

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.toolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal abstract class InteractionTool(
    override val name: String,
    override val description: String,
) : IdeTool {
    override val domain = ToolDomain.INTERACTION
    override val risk = Risk.INTERACTION
}

internal object AskUserTool : InteractionTool(
    "ask_user",
    "Asks the user a question inside the IDE and waits for the answer. Use it when a decision is theirs to make or " +
        "required information is missing. Offer 'options' when the choices are known.",
) {
    override val inputSchema =
        toolSchema {
            string("question", "The question, self-contained.", required = true)
            stringArray("options", "Choices to offer. The user may still answer in free text.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val answer = ctx.interaction.ask(arguments.string("question"), arguments.strings("options"))
        return if (answer == null) ToolResult("The user dismissed the question without answering.", isError = true) else ToolResult(answer)
    }
}

internal object RequestApprovalTool : InteractionTool(
    "request_approval",
    "Asks the user to approve something before you do it, when no other tool already asks for it. Returns approved or rejected.",
) {
    override val inputSchema =
        toolSchema {
            string("description", "What you intend to do and why, in one or two sentences.", required = true)
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val approved = ctx.interaction.approve(ToolArgs(args).string("description"))
        return ToolResult(if (approved) "approved" else "rejected", isError = !approved)
    }
}

internal object ShowNotificationTool : InteractionTool(
    "show_notification",
    "Shows a notification balloon in the IDE, e.g. to tell the user a long task finished. It does not wait for the user.",
) {
    /** Declared in plugin.xml. */
    const val GROUP_ID = "Conduit"

    override val inputSchema =
        toolSchema {
            string("message", "Text to show.", required = true)
            string("level", "info (default), warning or error.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val type =
            when (arguments.stringOrNull("level")?.lowercase()) {
                null, "info" -> NotificationType.INFORMATION
                "warning" -> NotificationType.WARNING
                "error" -> NotificationType.ERROR
                else -> throw ToolException("Unknown level. Use info, warning or error.")
            }
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(arguments.string("message"), type)
            .notify(project)
        return ToolResult("Shown.")
    }
}

internal object OpenFileAtTool : InteractionTool(
    "open_file_at",
    "Opens a file in the editor and moves the caret to a line, so the user sees the code you are talking about.",
) {
    override val inputSchema =
        toolSchema {
            string("file", "Project-relative or absolute path.", required = true)
            integer("line", "1-based line (default 1).")
            integer("column", "1-based column (default 1).")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("file")
        val file = readAction { ProjectFiles.require(project, path) }
        val line = (arguments.int("line", 1) - 1).coerceAtLeast(0)
        val column = (arguments.int("column", 1) - 1).coerceAtLeast(0)
        withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file, line, column).navigate(true) }
        return ToolResult("Opened $path:${line + 1}.")
    }
}
