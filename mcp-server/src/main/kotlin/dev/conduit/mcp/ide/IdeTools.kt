package dev.conduit.mcp.ide

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.conduit.core.Risk
import dev.conduit.mcp.McpTool
import dev.conduit.mcp.McpToolResult
import dev.conduit.mcp.ide.tools.ApplyPatchTool
import dev.conduit.mcp.ide.tools.AskUserTool
import dev.conduit.mcp.ide.tools.CallHierarchyTool
import dev.conduit.mcp.ide.tools.CreateFileTool
import dev.conduit.mcp.ide.tools.DependenciesTool
import dev.conduit.mcp.ide.tools.EndpointsTool
import dev.conduit.mcp.ide.tools.FileStructureTool
import dev.conduit.mcp.ide.tools.FindSymbolTool
import dev.conduit.mcp.ide.tools.FindUsagesTool
import dev.conduit.mcp.ide.tools.FrameworksDetectedTool
import dev.conduit.mcp.ide.tools.GetBuildErrorsTool
import dev.conduit.mcp.ide.tools.GetProblemsTool
import dev.conduit.mcp.ide.tools.GoToDefinitionTool
import dev.conduit.mcp.ide.tools.OpenFileAtTool
import dev.conduit.mcp.ide.tools.OpenFilesTool
import dev.conduit.mcp.ide.tools.ProjectModulesTool
import dev.conduit.mcp.ide.tools.ProposeEditTool
import dev.conduit.mcp.ide.tools.QuickFixTool
import dev.conduit.mcp.ide.tools.RenameSymbolTool
import dev.conduit.mcp.ide.tools.RequestApprovalTool
import dev.conduit.mcp.ide.tools.RunInspectionTool
import dev.conduit.mcp.ide.tools.SelectionTool
import dev.conduit.mcp.ide.tools.ShowNotificationTool
import dev.conduit.mcp.ide.tools.TypeHierarchyTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** The IDE tools offered to agents. */
object IdeTools {
    /**
     * Extension point through which optional parts of the plugin (language support, framework
     * packs) add tools that only exist when their IDE plugin is present.
     */
    val EP_NAME: ExtensionPointName<IdeTool> = ExtensionPointName.create("dev.conduit.ideTool")

    /** Tools available in every IDE. */
    val BUILT_IN: List<IdeTool> =
        listOf(
            FindSymbolTool,
            FindUsagesTool,
            GoToDefinitionTool,
            TypeHierarchyTool,
            CallHierarchyTool,
            FileStructureTool,
            RenameSymbolTool,
            ProposeEditTool,
            CreateFileTool,
            ApplyPatchTool,
            GetProblemsTool,
            GetBuildErrorsTool,
            RunInspectionTool,
            QuickFixTool,
            ProjectModulesTool,
            DependenciesTool,
            FrameworksDetectedTool,
            EndpointsTool,
            OpenFilesTool,
            SelectionTool,
            AskUserTool,
            RequestApprovalTool,
            ShowNotificationTool,
            OpenFileAtTool,
        )

    /** Built-in tools plus those contributed through [EP_NAME]. */
    fun all(): List<IdeTool> = BUILT_IN + EP_NAME.extensionsIfPointIsRegistered

    /** Binds [tools] to a project and session, as the MCP server needs them. */
    fun bind(
        project: Project,
        ctx: ToolContext,
        tools: List<IdeTool> = all(),
    ): List<McpTool> = tools.map { BoundTool(it, project, ctx) }

    private class BoundTool(
        private val tool: IdeTool,
        private val project: Project,
        private val ctx: ToolContext,
    ) : McpTool {
        override val name get() = tool.name
        override val description get() = tool.description
        override val inputSchema get() = tool.inputSchema
        override val readOnly get() = tool.risk == Risk.READ

        override suspend fun call(args: JsonObject): McpToolResult =
            try {
                val result = tool.invoke(project, args, ctx)
                McpToolResult(result.text, result.isError)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: ToolException) {
                McpToolResult.error(e.message ?: "The tool failed")
            }
    }
}
