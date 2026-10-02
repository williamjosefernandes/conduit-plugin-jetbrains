package dev.conduit.ide.review

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import dev.conduit.agents.AgentStartException
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.SessionId
import dev.conduit.core.UnifiedDiffFormat
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.agents.OneShotAgent
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.changes.Review
import dev.conduit.ide.edit.InlineEditService
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.tasks.TaskService
import dev.conduit.ide.vcs.CommandResult
import dev.conduit.ide.vcs.GhCli
import dev.conduit.ide.vcs.GitCli
import dev.conduit.ide.vcs.GitException
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.orchestrator.JsonBlocks
import dev.conduit.orchestrator.int
import dev.conduit.orchestrator.review.DiffStats
import dev.conduit.orchestrator.review.FindingStatus
import dev.conduit.orchestrator.review.ReviewFinding
import dev.conduit.orchestrator.review.ReviewPrompts
import dev.conduit.orchestrator.review.ReviewReport
import dev.conduit.orchestrator.review.ReviewTarget
import dev.conduit.orchestrator.review.ReviewTargetKind
import dev.conduit.orchestrator.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Code review by the user's agent: of the uncommitted changes, of the current branch against the
 * main one, or of a GitHub pull request when the `gh` CLI is installed and logged in. Findings are
 * shown in the Review tab and marked in the editor; fixes become reviewed changes.
 */
@Service(Service.Level.PROJECT)
class ReviewService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val reportState = MutableStateFlow<ReviewReport?>(null)
    private val runningState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val targetsState = MutableStateFlow<List<ReviewTarget>>(emptyList())
    private val targetState = MutableStateFlow<ReviewTarget?>(null)
    private val busyFindings = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    private var job: Job? = null

    @Volatile
    private var loaded = false

    /** The latest review, kept across restarts. */
    val report: StateFlow<ReviewReport?> = reportState.asStateFlow()
    val running: StateFlow<Boolean> = runningState.asStateFlow()
    val error: StateFlow<String?> = errorState.asStateFlow()

    /** What can be reviewed right now. */
    val targets: StateFlow<List<ReviewTarget>> = targetsState.asStateFlow()
    val target: StateFlow<ReviewTarget?> = targetState.asStateFlow()

    /** Findings for which a fix is being prepared. */
    val busy: StateFlow<Set<String>> = busyFindings.asStateFlow()

    fun start() {
        if (loaded) return
        loaded = true
        scope.launch {
            load()
            refreshTargets()
        }
    }

    /** Lists local changes, the branch and open pull requests. */
    suspend fun refreshTargets() {
        val dir = project.basePath ?: return
        if (!GitCli.isRepository(dir)) {
            targetsState.value = emptyList()
            return
        }
        val list = ArrayList<ReviewTarget>()
        list += ReviewTarget(ReviewTargetKind.LOCAL_CHANGES, ConduitBundle.message("review.target.local"))
        val branch = GitCli.currentBranch(dir)
        val main = GitCli.defaultBranch(dir)
        if (branch != null && main != null && branch != main) {
            list += ReviewTarget(ReviewTargetKind.BRANCH, ConduitBundle.message("review.target.branch", branch, main), main)
        }
        list += pullRequests(dir)
        targetsState.value = list
        val current = targetState.value
        if (current == null || list.none { it == current }) {
            targetState.value =
                reportState.value?.target?.takeIf { it in list } ?: list.firstOrNull { it.kind == ReviewTargetKind.PULL_REQUEST }
                    ?: list.firstOrNull { it.kind == ReviewTargetKind.BRANCH } ?: list.first()
        }
    }

    /** Lists the targets again, in the background. */
    fun reload() {
        scope.launch { refreshTargets() }
    }

    fun select(target: ReviewTarget) {
        targetState.value = target
    }

    /** Reviews [target] with the default agent. */
    fun run(
        target: ReviewTarget =
            targetState.value
                ?: ReviewTarget(ReviewTargetKind.LOCAL_CHANGES, ConduitBundle.message("review.target.local")),
    ) {
        if (runningState.value) return
        targetState.value = target
        errorState.value = null
        runningState.value = true
        job =
            scope.launch {
                try {
                    val (diff, base) = diffOf(target)
                    if (diff.isBlank()) throw GitException(ConduitBundle.message("review.error.nothing"))
                    val checklist = ConduitSettings.getInstance().checklist()
                    val agent = AgentRegistry.getInstance().defaultAgent()
                    val answer = OneShotAgent.ask(project, agent.id, ReviewPrompts.review(diff, checklist, TaskService.replyLanguage()))
                    val parsed =
                        try {
                            ReviewPrompts.parse(answer, checklist)
                        } catch (e: IllegalArgumentException) {
                            throw GitException(ConduitBundle.message("review.error.parse"), e)
                        }
                    val report =
                        ReviewReport(
                            id = "review-${UUID.randomUUID()}",
                            target = target,
                            summary = parsed.summary,
                            files = DiffStats.parse(diff),
                            findings = parsed.findings,
                            checklist = parsed.checklist,
                            agentName = agent.displayName,
                            createdAt = System.currentTimeMillis(),
                            baseRevision = base,
                        )
                    publish(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitException) {
                    errorState.value = e.message
                } catch (e: AgentStartException) {
                    errorState.value = e.message
                } catch (e: Exception) {
                    log.warn("Review failed", e)
                    errorState.value = e.message ?: e.javaClass.simpleName
                } finally {
                    runningState.value = false
                }
            }
    }

    fun cancel() {
        job?.cancel()
        runningState.value = false
    }

    fun dismissError() {
        errorState.value = null
    }

    /** Hides a finding; [reason] is kept with it. */
    fun ignore(
        findingId: String,
        reason: String?,
    ) = change { it.withFinding(findingId) { finding -> finding.copy(status = FindingStatus.IGNORED, ignoreReason = reason) } }

    fun reopen(findingId: String) =
        change {
            it.withFinding(findingId) { finding -> finding.copy(status = FindingStatus.OPEN, ignoreReason = null) }
        }

    fun markFixed(findingId: String) = change { it.withFinding(findingId) { finding -> finding.copy(status = FindingStatus.FIXED) } }

    fun toggleChecklist(index: Int) =
        change { report ->
            report.copy(checklist = report.checklist.mapIndexed { i, item -> if (i == index) item.copy(checked = !item.checked) else item })
        }

    fun complete() = change { it.copy(completed = true) }

    /**
     * Applies the reviewer's concrete suggestion when the code it quotes is still there, as a
     * reviewed change; otherwise asks the agent for a fix like [fix] does.
     */
    suspend fun applySuggestion(finding: ReviewFinding): Review? {
        val before = finding.before
        val after = finding.after
        val path = finding.file
        if (before.isNullOrEmpty() || after == null || path == null) return fix(finding)
        val current = readAction { ProjectFiles.find(project, path)?.let(ProjectFiles::text) } ?: return fix(finding)
        val at = current.indexOf(before).takeIf { it >= 0 } ?: current.indexOf(before.trim()).takeIf { it >= 0 } ?: return fix(finding)
        val length = if (current.startsWith(before, at)) before.length else before.trim().length
        val proposed = current.substring(0, at) + after + current.substring(at + length)
        val diff = PendingDiff.create(DiffId(UUID.randomUUID().toString()), SessionId("review"), path, current, proposed)
        val review = PendingChangesService.getInstance(project).enqueue(ProposedChangeSet(finding.title, listOf(diff)))
        watch(finding.id, review)
        return review
    }

    /** Asks the agent to fix the finding in the code around it; the fix becomes a reviewed change. */
    suspend fun fix(finding: ReviewFinding): Review? {
        val path = finding.file ?: return null
        busyFindings.update { it + finding.id }
        try {
            val target =
                readAction {
                    val file = ProjectFiles.find(project, path) ?: return@readAction null
                    val text = ProjectFiles.text(file)
                    val lines = text.split('\n')
                    val line = (finding.line ?: 1).coerceIn(1, lines.size)
                    val from = (line - FIX_CONTEXT_LINES).coerceAtLeast(1)
                    val to = (line + FIX_CONTEXT_LINES).coerceAtMost(lines.size)
                    val start = lines.take(from - 1).sumOf { it.length + 1 }
                    val end = (start + lines.subList(from - 1, to).joinToString("\n").length).coerceAtMost(text.length)
                    InlineEditService.Target(path, text, start, end, file.extension.orEmpty())
                } ?: return null
            val instruction =
                listOf(finding.title, finding.description, finding.recommendation).filter { it.isNotBlank() }.joinToString(". ")
            val review = InlineEditService.getInstance(project).edit(target, instruction)
            if (review != null) watch(finding.id, review)
            return review
        } finally {
            busyFindings.update { it - finding.id }
        }
    }

    /** The file of [path] in the reviewed base revision, for showing the diff. */
    suspend fun baseContent(path: String): String? {
        val dir = project.basePath ?: return null
        val base = reportState.value?.baseRevision ?: "HEAD"
        return GitCli.show(dir, base, path)?.replace("\r\n", "\n")
    }

    /** True when the review can be posted to its pull request with `gh`. */
    fun canPublish(): Boolean = reportState.value?.target?.kind == ReviewTargetKind.PULL_REQUEST && gh() != null

    /**
     * Posts the review as a comment on its pull request with the user's own `gh` login.
     * The caller confirms with the user first: this publishes outside the IDE.
     *
     * @throws GitException when posting fails
     */
    suspend fun publish() {
        val report = reportState.value ?: return
        val number = report.target.ref ?: throw GitException(ConduitBundle.message("review.error.notPr"))
        val dir = project.basePath ?: return
        val result = ghRun(dir, listOf("pr", "comment", number, "--body-file", "-"), report.toMarkdown())
        if (!result.ok) throw GitException(result.stderr.trim().ifEmpty { ConduitBundle.message("review.error.publish") })
    }

    private fun watch(
        findingId: String,
        review: Review,
    ) {
        scope.launch {
            val outcome = review.await()
            if (outcome == dev.conduit.mcp.ide.ReviewOutcome.APPLIED || outcome == dev.conduit.mcp.ide.ReviewOutcome.PARTIALLY_APPLIED) {
                markFixed(findingId)
            }
        }
    }

    private fun change(transform: (ReviewReport) -> ReviewReport) {
        val updated = reportState.value?.let(transform) ?: return
        publish(updated)
    }

    private fun publish(report: ReviewReport) {
        reportState.value = report
        scope.launch { save(report) }
    }

    /** The diff to review and the revision it is relative to. */
    private suspend fun diffOf(target: ReviewTarget): Pair<String, String?> {
        val dir = project.basePath ?: throw GitException("The project has no directory")
        return when (target.kind) {
            ReviewTargetKind.LOCAL_CHANGES -> {
                val tracked = GitCli.output(dir, "diff", "HEAD", "--relative")
                tracked + untrackedDiff(dir) to "HEAD"
            }
            ReviewTargetKind.BRANCH -> {
                val base = target.ref ?: GitCli.defaultBranch(dir) ?: throw GitException(ConduitBundle.message("review.error.noBase"))
                val mergeBase = GitCli.output(dir, "merge-base", base, "HEAD").trim()
                GitCli.output(dir, "diff", "$mergeBase...HEAD", "--relative") to mergeBase
            }
            ReviewTargetKind.PULL_REQUEST -> {
                val number = target.ref ?: throw GitException(ConduitBundle.message("review.error.notPr"))
                val result = ghRun(dir, listOf("pr", "diff", number))
                if (!result.ok) throw GitException(result.stderr.trim().ifEmpty { ConduitBundle.message("review.error.gh") })
                result.stdout to prBase(dir, number)
            }
        }
    }

    private suspend fun untrackedDiff(dir: String): String {
        val context =
            dev.conduit.ide.context.ContextService
                .getInstance(project)
        // IDE metadata and protected files are not part of the change under review.
        val paths =
            GitCli
                .run(dir, "ls-files", "--others", "--exclude-standard", "-z")
                .stdout
                .split('\u0000')
                .filter { it.isNotBlank() && !it.startsWith(".idea/") && !it.endsWith(".iml") && !context.isHidden(it) }
        val diffs =
            withContext(Dispatchers.IO) {
                paths.take(MAX_UNTRACKED).mapNotNull { path ->
                    val file = File(dir, path)
                    if (!file.isFile || file.length() > MAX_UNTRACKED_BYTES) return@mapNotNull null
                    val bytes = file.readBytes()
                    if (bytes.any { it == 0.toByte() }) return@mapNotNull null
                    PendingDiff.create(DiffId(path), SessionId("review"), path, null, String(bytes, StandardCharsets.UTF_8))
                }
            }
        // Headers like git's, so new files count in the statistics like tracked ones.
        return diffs.joinToString("") { diff ->
            "diff --git a/${diff.path} b/${diff.path}\nnew file mode 100644\n" +
                UnifiedDiffFormat.format(diff)
        }
    }

    private suspend fun prBase(
        dir: String,
        number: String,
    ): String? {
        val view = ghRun(dir, listOf("pr", "view", number, "--json", "baseRefName"))
        val base = (JsonBlocks.find(view.stdout) as? JsonObject)?.text("baseRefName") ?: return null
        return listOf("origin/$base", base).firstOrNull { GitCli.run(dir, "rev-parse", "--verify", "--quiet", it).ok }
    }

    private suspend fun pullRequests(dir: String): List<ReviewTarget> {
        if (gh() == null) return emptyList()
        val result =
            runCatching {
                ghRun(
                    dir,
                    listOf("pr", "list", "--state", "open", "--limit", "20", "--json", "number,title"),
                )
            }.getOrNull()
        if (result == null || !result.ok) return emptyList()
        val array = JsonBlocks.find(result.stdout) as? JsonArray ?: return emptyList()
        return array.filterIsInstance<JsonObject>().mapNotNull { pr ->
            val number = pr.int("number") ?: return@mapNotNull null
            ReviewTarget(
                ReviewTargetKind.PULL_REQUEST,
                ConduitBundle.message("review.target.pr", number, pr.text("title").orEmpty()),
                number.toString(),
            )
        }
    }

    private fun gh(): String? = GhCli.executable()

    private suspend fun ghRun(
        dir: String,
        args: List<String>,
        input: String? = null,
    ): CommandResult = GhCli.run(dir, args, input)

    private suspend fun load() {
        val stored =
            withContext(Dispatchers.IO) {
                val file = storage()
                if (!Files.exists(file)) return@withContext null
                runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
                        ReviewReport.serializer(),
                        file.readText(),
                    )
                }.getOrNull()
            }
        if (stored != null && reportState.value == null) reportState.value = stored
    }

    private suspend fun save(report: ReviewReport) {
        withContext(Dispatchers.IO) {
            try {
                val file = storage()
                Files.createDirectories(file.parent)
                file.writeText(
                    kotlinx.serialization.json.Json
                        .encodeToString(ReviewReport.serializer(), report),
                )
            } catch (e: java.io.IOException) {
                log.warn("Could not save the review", e)
            }
        }
    }

    private fun storage(): Path = Path.of(PathManager.getSystemPath(), "conduit", "reviews", "${project.getProjectCacheFileName()}.json")

    companion object {
        private val log = logger<ReviewService>()
        private const val FIX_CONTEXT_LINES = 12
        private const val MAX_UNTRACKED = 50
        private const val MAX_UNTRACKED_BYTES = 256 * 1024L

        fun getInstance(project: Project): ReviewService = project.service()
    }
}
