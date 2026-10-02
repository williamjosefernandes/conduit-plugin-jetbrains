package dev.conduit.ide.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.McpServerConfig
import dev.conduit.agents.SessionConfig
import dev.conduit.agents.StdioCommand
import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode
import dev.conduit.core.ErrorCode
import dev.conduit.core.Failure
import dev.conduit.core.Risk
import dev.conduit.core.SessionId
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.mcp.bridge.StdioBridge
import dev.conduit.mcp.ide.ConduitMcpService
import dev.conduit.mcp.ide.IdeTools
import dev.conduit.mcp.ide.McpBinding
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.orchestrator.chat.ChatController
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.policy.PolicyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opens and tracks this project's conversations. Launching an agent wires everything a session
 * needs: the agent process, the session's policy, its access to the IDE tools over MCP, the
 * file system bridge and the controller the UI observes. Tasks launch their agents here too.
 */
@Service(Service.Level.PROJECT)
class ChatSessionManager(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val open = MutableStateFlow<List<ChatSession>>(emptyList())
    private val counter = AtomicInteger()

    /** The open conversations, oldest first. */
    val sessions: StateFlow<List<ChatSession>> = open.asStateFlow()

    /**
     * Starts [agentId] in the project directory and returns the conversation.
     *
     * @param resume id of a stored conversation to continue; the agent reloads its own session
     *   when it can, and the stored transcript is shown either way
     * @throws AgentStartException (or a subclass saying why) if the agent cannot be started
     */
    suspend fun open(
        agentId: AgentId,
        mode: AgentMode = AgentMode.AGENT,
        resume: String? = null,
    ): ChatSession {
        val cwd =
            project.basePath
                ?: throw AgentStartException(
                    "The project has no directory for the agent to work in",
                    failure = Failure(ErrorCode.NO_PROJECT_DIR),
                )
        val history = ConversationHistory.getInstance(project)
        if (resume != null && !history.isLoaded) history.refresh()
        val stored = resume?.let(history::find)
        val runtime = launch(agentId, mode, cwd, resumeToken = stored?.resumeToken, initial = stored?.state ?: ChatState())
        lateinit var chat: ChatSession
        chat =
            ChatSession(
                project = project,
                runtime = runtime,
                historyId = stored?.id ?: "chat-${UUID.randomUUID()}",
                createdAt = stored?.createdAt ?: System.currentTimeMillis(),
                onTurnEnd = history::save,
                onClose = { session ->
                    history.save(session)
                    open.update { it - session }
                },
            )
        open.update { it + chat }
        return chat
    }

    /**
     * Starts an agent process in [cwd] with its policy, IDE tools and controller.
     *
     * @param inWorktree whether [cwd] is an isolated git worktree, which the policy takes into account
     * @throws AgentStartException (or a subclass saying why) if the agent cannot be started
     */
    suspend fun launch(
        agentId: AgentId,
        mode: AgentMode,
        cwd: String,
        inWorktree: Boolean = false,
        resumeToken: String? = null,
        initial: ChatState = ChatState(),
    ): AgentRuntime {
        val registry = AgentRegistry.getInstance()
        val spec =
            registry.spec(agentId)
                ?: throw AgentStartException(
                    "Unknown agent ${agentId.value}",
                    failure = Failure(ErrorCode.AGENT_NOT_FOUND, listOf(agentId.value)),
                )
        val adapter =
            registry.adapter(agentId)
                ?: throw AgentStartException(
                    "Unknown agent ${agentId.value}",
                    failure = Failure(ErrorCode.AGENT_NOT_FOUND, listOf(agentId.value)),
                )
        val settings = ConduitSettings.getInstance()

        val id = SessionId("session-${counter.incrementAndGet()}")
        val modeState = MutableStateFlow(mode)
        val policy = PolicyEngine(settings.policyRules(), { modeState.value }, { settings.state.autonomy }, inWorktree)
        val changes = PendingChangesService.getInstance(project)
        val interaction = SessionInteraction()
        val mcp = ConduitMcpService.getInstance()
        val binding = mcp.open(project, ToolContext(id, policy, changes, interaction))

        val config =
            SessionConfig(
                cwd = cwd,
                mode = mode,
                mcpServers = listOf(mcpServer(binding)),
                fileSystem = IdeFileSystem(project, id, policy, changes),
                model = settings.state.models[agentId.value],
                resumeToken = resumeToken,
            )
        val agentSession =
            try {
                adapter.start(config)
            } catch (e: Throwable) {
                mcp.close(binding.token)
                throw e
            }

        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        val audit = AuditLog.getInstance(project)
        val controller =
            ChatController(
                agentSession,
                policy,
                sessionScope,
                ::isOwnTool,
                { event -> onAgentEvent(cwd, event, spec.displayName, audit) },
                initial = initial.copy(running = false),
            )
        interaction.controller = controller
        controller.start()
        audit.record(AuditLog.Kind.SESSION, "${spec.displayName} started in $cwd")
        return AgentRuntime(id, spec, policy, agentSession, controller, modeState, cwd, sessionScope) {
            mcp.close(binding.token)
            sessionScope.cancel()
            audit.record(AuditLog.Kind.SESSION, "${spec.displayName} stopped")
        }
    }

    override fun dispose() {
        // The scope is cancelled with the project, which ends the agent processes; tokens are revoked here.
        open.value.forEach { session -> scope.launch { runCatching { session.close() } } }
    }

    /** Agents write to disk themselves once approved; the IDE has to be told to look. */
    private fun onAgentEvent(
        cwd: String,
        event: AgentEvent,
        agent: String,
        audit: AuditLog,
    ) {
        audit.agentEvent(agent, event)
        val wrote = event is AgentEvent.ToolResult || event is AgentEvent.Done
        val base = LocalFileSystem.getInstance().findFileByPath(cwd)
        if (wrote && base != null) VfsUtil.markDirtyAndRefresh(true, true, true, base)
    }

    /**
     * True for the agent's permission prompts about Conduit's own IDE tools. Those tools apply
     * the policy and the review themselves, so asking again at the agent level would only make
     * the user approve the same thing twice.
     */
    private fun isOwnTool(request: AgentEvent.ApprovalRequest): Boolean {
        val label = request.tool ?: request.description
        return label.contains(ConduitMcpService.SERVER_NAME, ignoreCase = true) && ownToolNames.any { label.contains(it) }
    }

    private fun mcpServer(binding: McpBinding): McpServerConfig {
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java").toString()
        val bridge =
            StdioCommand(
                command = java,
                args = listOf("-cp", PathManager.getJarPathForClass(StdioBridge::class.java).orEmpty(), StdioBridge::class.java.name),
                env = mapOf("CONDUIT_MCP_URL" to binding.url, "CONDUIT_MCP_TOKEN" to binding.token),
            )
        return McpServerConfig(
            name = ConduitMcpService.SERVER_NAME,
            url = binding.url,
            headers = mapOf("Authorization" to "Bearer ${binding.token}"),
            stdioFallback = bridge,
        )
    }

    companion object {
        private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        private val ownToolNames by lazy { IdeTools.BUILT_IN.filter { it.risk != Risk.EXECUTE }.map { it.name } }

        fun getInstance(project: Project): ChatSessionManager = project.service()
    }
}
