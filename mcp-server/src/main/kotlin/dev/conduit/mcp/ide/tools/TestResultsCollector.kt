package dev.conduit.mcp.ide.tools

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One test of a finished run. */
data class TestOutcome(
    val name: String,
    val passed: Boolean,
    val durationMs: Long?,
    val message: String?,
    val stackTrace: String?,
)

/** A finished test run, whatever the framework: every IDE test runner reports through the same events. */
data class TestRun(
    val name: String,
    val tests: List<TestOutcome>,
    val finishedAtMs: Long,
) {
    val failed: List<TestOutcome> get() = tests.filter { !it.passed }
}

/** Keeps the last test runs of the project so agents can read results without re-running anything. */
@Service(Service.Level.PROJECT)
class TestResultsCollector(
    project: Project,
) : Disposable {
    private val runs = MutableStateFlow<List<TestRun>>(emptyList())

    /** The most recent runs, newest last. */
    val history: StateFlow<List<TestRun>> = runs.asStateFlow()

    init {
        project.messageBus.connect(this).subscribe(
            SMTRunnerEventsListener.TEST_STATUS,
            object : SMTRunnerEventsAdapter() {
                override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) = record(testsRoot)
            },
        )
    }

    /** Stores the outcome of [root]'s leaf tests. */
    fun record(root: SMTestProxy) {
        val tests =
            root.allTests.filter { it.isLeaf && it !== root }.map { test ->
                TestOutcome(
                    name = listOfNotNull(test.parent?.takeIf { it !== root }?.name, test.name).joinToString("."),
                    passed = !test.isDefect,
                    durationMs = test.duration,
                    message = test.errorMessage,
                    stackTrace = test.stacktrace,
                )
            }
        // The root's name is a placeholder; its presentation is what the run is called (the configuration name).
        val name = (root as? SMTestProxy.SMRootTestProxy)?.presentation?.takeIf { it.isNotBlank() } ?: root.name.orEmpty()
        runs.value = (runs.value + TestRun(name, tests, System.currentTimeMillis())).takeLast(MAX_RUNS)
    }

    override fun dispose() = Unit

    companion object {
        private const val MAX_RUNS = 10

        fun getInstance(project: Project): TestResultsCollector = project.service()
    }
}

/** Starts listening for test runs as soon as the project is open. */
class TestResultsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        TestResultsCollector.getInstance(project)
    }
}
