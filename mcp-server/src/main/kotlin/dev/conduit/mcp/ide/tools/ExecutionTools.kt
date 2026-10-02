package dev.conduit.mcp.ide.tools

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.conduit.core.ActionKind
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.Page
import dev.conduit.mcp.ide.SymbolLocator
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.authorize
import dev.conduit.mcp.ide.toolSchema
import dev.conduit.policy.ActionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

internal abstract class ExecutionTool(
    override val name: String,
    override val description: String,
    override val risk: Risk = Risk.EXECUTE,
) : IdeTool {
    override val domain = ToolDomain.EXECUTION
}

/** Runs IDE run configurations and waits for them, collecting what they print. */
object Runner {
    private const val MAX_OUTPUT_CHARS = 12_000
    const val DEFAULT_TIMEOUT_SECONDS = 180
    const val MAX_TIMEOUT_SECONDS = 1_800

    class Finished(
        val exitCode: Int?,
        val output: String,
    )

    /**
     * Starts [settings] with the Run executor and waits up to [timeoutSeconds].
     *
     * @return the exit code and the tail of the output; a null exit code means it is still running
     * @throws ToolException if the configuration cannot be started
     */
    suspend fun run(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        timeoutSeconds: Int,
    ): Finished {
        val output = StringBuilder()
        val exit = CompletableDeferred<Int>()
        val connection = project.messageBus.connect()
        connection.subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarted(
                    executorId: String,
                    env: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) {
                    if (env.runnerAndConfigurationSettings !== settings) return
                    handler.addProcessListener(
                        object : ProcessListener {
                            override fun onTextAvailable(
                                event: ProcessEvent,
                                outputType: Key<*>,
                            ) {
                                synchronized(output) {
                                    output.append(event.text)
                                    if (output.length > MAX_OUTPUT_CHARS * 2) output.delete(0, output.length - MAX_OUTPUT_CHARS)
                                }
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                exit.complete(event.exitCode)
                            }
                        },
                    )
                }

                override fun processNotStarted(
                    executorId: String,
                    env: ExecutionEnvironment,
                    cause: Throwable?,
                ) {
                    if (env.runnerAndConfigurationSettings === settings) {
                        exit.completeExceptionally(
                            ToolException("'${settings.name}' could not be started: ${cause?.message ?: "unknown reason"}"),
                        )
                    }
                }
            },
        )
        try {
            withContext(Dispatchers.EDT) {
                val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
                ProgramRunnerUtil.executeConfiguration(environment, false, true)
            }
            val code = withTimeoutOrNull(timeoutSeconds.seconds) { exit.await() }
            return Finished(code, synchronized(output) { output.toString().takeLast(MAX_OUTPUT_CHARS) })
        } catch (e: com.intellij.execution.ExecutionException) {
            throw ToolException("'${settings.name}' could not be started: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    fun describe(
        name: String,
        finished: Finished,
        timeoutSeconds: Int,
    ): ToolResult {
        val status = finished.exitCode?.let { "exit code $it" } ?: "still running after $timeoutSeconds s (it keeps running in the IDE)"
        return ToolResult("'$name': $status\n${finished.output}".trim(), isError = finished.exitCode != null && finished.exitCode != 0)
    }
}

internal object ListRunConfigsTool : ExecutionTool(
    "list_run_configs",
    "Lists the IDE's run configurations (applications, tests, build tasks) by name and type; the selected one is marked. " +
        "Use run_config to run one.",
    Risk.READ,
) {
    override val inputSchema = toolSchema { paging() }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val lines =
            readAction {
                val manager = RunManager.getInstance(project)
                val selected = manager.selectedConfiguration
                manager.allSettings.map { (if (it === selected) "* " else "  ") + "${it.name}  [${it.type.displayName}]" }
            }
        return Page.from(ToolArgs(args)).render(lines, "The project has no run configurations.")
    }
}

internal object RunConfigTool : ExecutionTool(
    "run_config",
    "Runs a run configuration by name, the way the Run button does, waits for it to finish and returns its exit code and " +
        "the end of its output. The user may be asked to allow it.",
) {
    override val inputSchema =
        toolSchema {
            string("name", "Name of the run configuration, as listed by list_run_configs.", required = true)
            integer("timeout_seconds", "How long to wait (default ${Runner.DEFAULT_TIMEOUT_SECONDS}).")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val name = arguments.string("name")
        val timeout = arguments.int("timeout_seconds", Runner.DEFAULT_TIMEOUT_SECONDS).coerceIn(1, Runner.MAX_TIMEOUT_SECONDS)
        val settings =
            readAction { RunManager.getInstance(project).findConfigurationByName(name) }
                ?: throw ToolException("No run configuration named '$name'. Use list_run_configs to see the names.")
        ctx.authorize(ActionRequest(ActionKind.RUN_TESTS), "Run '$name'")
        return Runner.describe(name, Runner.run(project, settings, timeout), timeout)
    }
}

internal object RunTestsTool : ExecutionTool(
    "run_tests",
    "Runs tests and returns which passed and which failed, with failure messages. Give 'config' to run an existing test " +
        "run configuration, or 'file' (optionally with 'line' or 'symbol') to run the tests of a file, class or method. " +
        "The user may be asked to allow it.",
) {
    override val inputSchema =
        toolSchema {
            string("config", "Name of a test run configuration.")
            symbolLocation()
            integer("timeout_seconds", "How long to wait (default ${Runner.DEFAULT_TIMEOUT_SECONDS}).")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val timeout = arguments.int("timeout_seconds", Runner.DEFAULT_TIMEOUT_SECONDS).coerceIn(1, Runner.MAX_TIMEOUT_SECONDS)
        val settings = settings(project, arguments, ctx)
        ctx.authorize(ActionRequest(ActionKind.RUN_TESTS), "Run tests '${settings.name}'")
        val collector = TestResultsCollector.getInstance(project)
        val before = collector.history.value.size
        val finished = Runner.run(project, settings, timeout)
        val run =
            collector.history.value
                .drop(before)
                .lastOrNull() ?: return Runner.describe(settings.name, finished, timeout)
        return GetTestResultsTool.format(run, Page.from(arguments))
    }

    private suspend fun settings(
        project: Project,
        arguments: ToolArgs,
        ctx: ToolContext,
    ): RunnerAndConfigurationSettings {
        arguments.stringOrNull("config")?.let { name ->
            return readAction { RunManager.getInstance(project).findConfigurationByName(name) }
                ?: throw ToolException("No run configuration named '$name'.")
        }
        if (arguments.stringOrNull("file") == null) throw ToolException("Give 'config' or 'file'")
        // Every language plugin contributes producers that know how to run the tests at a PSI element.
        return smartReadAction(project) {
            val element =
                if (arguments.intOrNull("line") != null || arguments.stringOrNull("symbol") != null) {
                    SymbolLocator.locate(project, arguments, ctx)
                } else {
                    dev.conduit.mcp.ide.ProjectFiles.psi(
                        project,
                        dev.conduit.mcp.ide.ProjectFiles
                            .require(project, arguments.string("file")),
                    )
                }
            ConfigurationContext(element).configuration ?: throw ToolException("The IDE does not know how to run tests there")
        }
    }
}

internal object GetTestResultsTool : ExecutionTool(
    "get_test_results",
    "Returns the results of the most recent test run in the IDE (run by you or by the user): totals and, for each failed " +
        "test, its message and stack trace. Nothing is executed.",
    Risk.READ,
) {
    private const val MAX_TRACE_LINES = 12

    override val inputSchema = toolSchema { paging() }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val run =
            TestResultsCollector
                .getInstance(project)
                .history.value
                .lastOrNull()
                ?: return ToolResult("No test run has finished in this IDE session yet.")
        return format(run, Page.from(ToolArgs(args)))
    }

    fun format(
        run: TestRun,
        page: Page,
    ): ToolResult {
        val header = "${run.name}: ${run.tests.size - run.failed.size} passed, ${run.failed.size} failed"
        if (run.failed.isEmpty()) return ToolResult(header)
        val failures =
            run.failed.map { test ->
                val trace =
                    test.stackTrace
                        ?.lineSequence()
                        ?.take(MAX_TRACE_LINES)
                        ?.joinToString("\n    ")
                listOfNotNull("FAILED ${test.name}", test.message?.let { "  $it" }, trace?.let { "    $it" }).joinToString("\n")
            }
        val body = page.render(failures, "")
        return ToolResult("$header\n${body.text}", isError = true)
    }
}
