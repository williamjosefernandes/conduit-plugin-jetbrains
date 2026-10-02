package dev.conduit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.AgentEvent
import dev.conduit.core.FileChange
import dev.conduit.core.FileChangeStatus
import dev.conduit.core.Plan
import dev.conduit.core.SessionId
import dev.conduit.core.Step
import dev.conduit.core.StepId
import dev.conduit.core.StepStatus
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.i18n.ConduitLanguage
import dev.conduit.ide.i18n.ConduitLanguageService
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.review.ChecklistItem
import dev.conduit.orchestrator.review.FindingKind
import dev.conduit.orchestrator.review.ReviewFinding
import dev.conduit.orchestrator.review.ReviewReport
import dev.conduit.orchestrator.review.ReviewTarget
import dev.conduit.orchestrator.review.ReviewTargetKind
import dev.conduit.orchestrator.review.Severity
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.TaskApproval
import dev.conduit.orchestrator.task.TaskApprovalKind
import dev.conduit.orchestrator.task.TaskCategory
import dev.conduit.orchestrator.task.TaskPhase
import dev.conduit.orchestrator.task.TaskStep
import dev.conduit.ui.actions.ActionsTab
import dev.conduit.ui.agent.AgentView
import dev.conduit.ui.chat.ChatPreview
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.context.ContextTab
import dev.conduit.ui.files.FilesTab
import dev.conduit.ui.review.ReviewView
import dev.conduit.ui.settings.GeneralPage
import dev.conduit.ui.tasks.TasksView
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders every screen of the Conduit tool window and the main settings page off-screen, with
 * the real Compose runtime and the IDE theme bridge, into `build/screenshots/` so they can be
 * compared with the design references. Fails if a screen cannot be composed or draws nothing.
 *
 * The conversation, tasks and review shown are fixtures of this test.
 */
class ScreensRenderTest : BasePlatformTestCase() {
    fun testChat() {
        val id = SessionId("s")
        val now = System.currentTimeMillis()
        var state =
            ChatState().userSaid(
                "Implement an endpoint to create a user with e-mail validation. Use the project's pattern and add tests.",
                at = now,
            )
        state =
            state.reduce(
                AgentEvent.Text(
                    id,
                    "Sure! I will implement the user creation endpoint with e-mail validation, following the project's pattern.",
                ),
                now,
            )
        state =
            state.reduce(
                AgentEvent.PlanUpdated(
                    id,
                    Plan(
                        listOf(
                            "Analyse the project",
                            "Implement the endpoint and validation",
                            "Add automated tests",
                            "Run the tests",
                        ).mapIndexed {
                            i,
                            title,
                            ->
                            Step(StepId("s$i"), title, StepStatus.DONE)
                        },
                    ),
                ),
                now,
            )
        state = state.reduce(AgentEvent.Text(id, "Done! I implemented the endpoint and added the tests."), now)
        val turn = state.lastTurn!!.id
        state =
            state
                .turnChanges(
                    turn,
                    listOf(
                        FileChange("src/main/kotlin/com/conduit/demo/controller/UserController.kt", FileChangeStatus.MODIFIED, 12, 2),
                        FileChange("src/main/kotlin/com/conduit/demo/dto/request/CreateUserRequest.kt", FileChangeStatus.MODIFIED, 8, 0),
                        FileChange("src/main/kotlin/com/conduit/demo/dto/response/UserResponse.kt", FileChangeStatus.MODIFIED, 6, 0),
                        FileChange("src/main/kotlin/com/conduit/demo/service/UserService.kt", FileChangeStatus.MODIFIED, 10, 0),
                        FileChange("src/test/kotlin/com/conduit/demo/controller/UserControllerTest.kt", FileChangeStatus.ADDED, 42, 0),
                    ),
                ).reduce(AgentEvent.Done(id, dev.conduit.core.StopReason.END_TURN))
        render("01-chat.png", ConduitTab.CHAT) { ChatPreview(project, state) }
    }

    fun testAgent() {
        val now = System.currentTimeMillis()
        val steps =
            listOf(
                TaskStep(
                    "1",
                    "Analyse files and understand the context",
                    "Find files related to authentication, user and e-mail.",
                    StepStatus.DONE,
                    now - 140_000,
                    now - 128_000,
                ),
                TaskStep(
                    "2",
                    "Create the reset request endpoint",
                    "Implement POST /api/auth/forgot-password.",
                    StepStatus.DONE,
                    now - 128_000,
                    now - 100_000,
                ),
                TaskStep(
                    "3",
                    "Implement the password reset service",
                    "Create a method to generate the token and send the e-mail.",
                    StepStatus.RUNNING,
                    now - 100_000,
                ),
                TaskStep("4", "Add token and new password validation", "Implement the reset endpoint and its security checks."),
                TaskStep("5", "Write automated tests", "Controller and service tests for the whole flow."),
                TaskStep("6", "Review the code and run the final verification", "Build, tests and coverage analysis."),
            )
        val task =
            AgentTask(
                id = "t",
                title = "Implement the password reset flow",
                prompt =
                    "Implement the complete e-mail password reset flow, including the request endpoint, token generation " +
                        "and validation, e-mail sending and automated tests.",
                agentId = "claude-code",
                agentName = "Claude Code",
                phase = TaskPhase.RUNNING,
                steps = steps,
                risks = listOf("The e-mail service may need environment variables in the test environment."),
                approvals = listOf(TaskApproval(TaskApprovalKind.WORKTREE)),
                createdAt = now - 200_000,
                activeMs = 134_000,
                currentActivity = "Implementing createResetToken in PasswordResetService…",
            )
        render("02-agent.png", ConduitTab.AGENT) { AgentView(project, task, null, now) }
    }

    fun testReview() {
        val report = reviewFixture()
        render("03-review.png", ConduitTab.REVIEW) { ReviewView(project, report, false, null, report.target, emptySet()) }
    }

    private fun reviewFixture(): ReviewReport =

        ReviewReport(
            id = "r",
            target = ReviewTarget(ReviewTargetKind.PULL_REQUEST, "PR #12 – User creation", "12"),
            summary = "We analysed 3 changed files against your guidelines and the team's good practices.",
            files = listOf(FileChange("src/main/kotlin/com/conduit/demo/service/UserService.kt", FileChangeStatus.MODIFIED, 12, 3)),
            findings =
                listOf(
                    ReviewFinding(
                        "1",
                        FindingKind.ISSUE,
                        Severity.HIGH,
                        "Missing input validation",
                        "src/main/kotlin/UserService.kt",
                        17,
                        "The e-mail is accepted without proper validation.",
                    ),
                    ReviewFinding(
                        "2",
                        FindingKind.SUGGESTION,
                        Severity.MEDIUM,
                        "Naming suggestion",
                        "src/main/kotlin/UserService.kt",
                        21,
                        "Consider a more descriptive name.",
                        before = "val user",
                        after = "val newUser",
                    ),
                    ReviewFinding(
                        "3",
                        FindingKind.ISSUE,
                        Severity.HIGH,
                        "Missing null handling",
                        "src/main/kotlin/UserService.kt",
                        27,
                        "findById may return null.",
                    ),
                    ReviewFinding(
                        "4",
                        FindingKind.OBSERVATION,
                        Severity.LOW,
                        "Test coverage",
                        "src/main/kotlin/UserService.kt",
                        null,
                        "No tests were found for the invalid e-mail case.",
                    ),
                ),
            checklist =
                listOf(
                    ChecklistItem("Input validation (e-mail, required fields)", true),
                    ChecklistItem("Error and exception handling", true),
                    ChecklistItem("Naming and readability", true),
                    ChecklistItem("Test coverage", false),
                ),
            agentName = "Claude Code",
            createdAt = System.currentTimeMillis(),
        )

    fun testTasks() {
        val now = System.currentTimeMillis()

        fun task(
            title: String,
            phase: TaskPhase,
            category: TaskCategory,
            agent: String,
            done: Int = 0,
        ) = AgentTask(
            id = title,
            title = title,
            prompt = "$title, following the project's patterns.",
            agentId = agent,
            agentName = agent,
            category = category,
            phase = phase,
            steps = (1..5).map { TaskStep("$it", "Step $it", status = if (it <= done) StepStatus.DONE else StepStatus.PENDING) },
            createdAt = now - 600_000,
            updatedAt = now - 480_000,
            activeMs = 240_000,
            costUsd = 0.02,
            branch = "feat/${title.lowercase().replace(' ', '-').take(16)}",
        )
        val tasks =
            listOf(
                task("Update the users API documentation", TaskPhase.QUEUED, TaskCategory.DOCUMENTATION, "Claude Code"),
                task("Migrate legacy endpoint", TaskPhase.QUEUED, TaskCategory.MIGRATION, "Gemini CLI"),
                task("Refactor the authentication module", TaskPhase.RUNNING, TaskCategory.REFACTOR, "Claude Code", 3),
                task("Generate UserService tests", TaskPhase.RUNNING, TaskCategory.TESTS, "Codex CLI", 2),
                task("Implement e-mail validation", TaskPhase.IN_REVIEW, TaskCategory.FEATURE, "Claude Code", 5),
                task("Fix the e-mail sending failure", TaskPhase.DONE, TaskCategory.FIX, "Codex CLI", 5),
                task("Adjust the response mapping", TaskPhase.DONE, TaskCategory.FIX, "Gemini CLI", 5),
            )
        render("04-tasks.png", ConduitTab.TASKS) { TasksView(project, tasks, now) }
    }

    fun testContext() {
        myFixture.configureByText("UserController.kt", "package demo\n\nclass UserController {\n    fun create() = Unit\n}\n")
        ContextService.getInstance(project).start()
        render("05-context.png", ConduitTab.CONTEXT) { ContextTab(project) }
    }

    fun testFiles() {
        myFixture.configureByText("UserController.kt", "package demo\n\nclass UserController\n")
        render("06-files.png", ConduitTab.FILES) { FilesTab(project) }
    }

    fun testActions() {
        render("07-actions.png", ConduitTab.ACTIONS) { ActionsTab(project) }
    }

    fun testSettings() {
        // As the Settings dialog does when it opens: the page shows what is stored.
        render("08-settings-general.png", null) { remember { GeneralPage().apply { reset() } }.Preview() }
    }

    private var storedLanguage: String? = null

    override fun setUp() {
        super.setUp()
        storedLanguage = ConduitSettings.getInstance().state.language
    }

    override fun tearDown() {
        try {
            ConduitSettings.getInstance().state.language = storedLanguage
            ConduitLanguageService.getInstance().reload()
        } finally {
            super.tearDown()
        }
    }

    /** Renders the screen once per language of the interface, as `name-en.png` and `name-pt_BR.png`. */
    private fun render(
        name: String,
        tab: ConduitTab?,
        content: @Composable () -> Unit,
    ) {
        ConduitLanguage.entries.forEach { language ->
            ConduitLanguageService.getInstance().select(language)
            renderIn(name.replace(".png", "-${language.code}.png"), tab, content)
        }
    }

    private fun renderIn(
        name: String,
        tab: ConduitTab?,
        content: @Composable () -> Unit,
    ) {
        tab?.let { ConduitNavigation.getInstance(project).select(it) }
        val scene = ImageComposeScene(width = if (tab == null) SETTINGS_WIDTH else WIDTH, height = HEIGHT, density = Density(1f))
        try {
            scene.setContent {
                SwingBridgeTheme {
                    ProvideMarkdownStyling(project) {
                        Column(Modifier.fillMaxSize().background(ConduitColors.panel)) {
                            if (tab != null) TabStrip(project, tab) {}
                            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                        }
                    }
                }
            }
            // Markdown and the context sources are computed off the UI thread; render again once they are in.
            scene.render()
            // The test runs on the EDT, which the screens' background work needs too: keep it pumping.
            repeat(SETTLE_STEPS) { step ->
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(SETTLE_MS / SETTLE_STEPS)
                scene.render((step + 1) * SETTLE_MS / SETTLE_STEPS * NANOS_PER_MS)
            }
            val bytes =
                checkNotNull(
                    scene.render(2 * SETTLE_MS * NANOS_PER_MS).encodeToData(EncodedImageFormat.PNG),
                ) { "PNG encoding failed" }.bytes
            val out = File("build/screenshots").apply { mkdirs() }.resolve(name)
            out.writeBytes(bytes)
            assertTrue("$name is empty", bytes.size > MIN_PNG_BYTES)
            println("Screenshot: ${out.absolutePath}")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 580
        const val SETTINGS_WIDTH = 900
        const val HEIGHT = 900
        const val MIN_PNG_BYTES = 4_000
        const val SETTLE_MS = 3_000L
        const val SETTLE_STEPS = 30
        const val NANOS_PER_MS = 1_000_000L
    }
}
