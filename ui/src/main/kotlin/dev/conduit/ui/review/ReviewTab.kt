package dev.conduit.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.review.ReviewService
import dev.conduit.orchestrator.review.FindingKind
import dev.conduit.orchestrator.review.FindingStatus
import dev.conduit.orchestrator.review.ReviewFinding
import dev.conduit.orchestrator.review.ReviewReport
import dev.conduit.orchestrator.review.Severity
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.Banner
import dev.conduit.ui.components.Card
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.EmptyState
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.LineDelta
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.VSpace
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.DefaultSplitButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** The Review tab: a code review by the agent, with its findings, checklist and actions. */
@Composable
fun ReviewTab(project: Project) {
    val service = ReviewService.getInstance(project)
    val report by service.report.collectAsState()
    val running by service.running.collectAsState()
    val error by service.error.collectAsState()
    val target by service.target.collectAsState()
    val busy by service.busy.collectAsState()
    ReviewView(project, report, running, error, target, busy)
}

/** The Review tab for [report]; [ReviewTab] feeds it from the review service. */
@Composable
fun ReviewView(
    project: Project,
    report: ReviewReport?,
    running: Boolean,
    error: String?,
    target: dev.conduit.orchestrator.review.ReviewTarget?,
    busy: Set<String>,
) {
    val service = ReviewService.getInstance(project)
    Column(Modifier.fillMaxSize().padding(ConduitDimens.pad)) {
        error?.let { Banner(it, ConduitColors.error, onDismiss = service::dismissError) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = ConduitDimens.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        ) {
            Text(
                target?.label ?: ConduitBundle.message("review.target.none"),
                style = ConduitType.small,
                color = ConduitColors.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                CircularProgressIndicator(Modifier.size(14.dp))
                Text(
                    ConduitBundle.message("review.running", AgentRegistry.getInstance().defaultAgent().displayName),
                    style = ConduitType.small,
                    color = ConduitColors.secondary,
                )
                OutlinedButton(onClick = service::cancel) { Text(ConduitBundle.message("tasks.cancel")) }
            } else {
                OutlinedButton(onClick = { service.run() }, enabled = target != null) {
                    IconText(AllIconsKeys.Actions.Refresh, ConduitBundle.message(if (report == null) "review.run" else "review.again"))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val current = report
            if (current == null) {
                EmptyState(
                    AllIconsKeys.Toolwindows.ToolWindowInspection,
                    ConduitBundle.message("review.empty.title"),
                    ConduitBundle.message("review.empty.description"),
                )
            } else {
                VerticallyScrollableContainer(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().padding(end = ConduitDimens.gap),
                        verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
                    ) {
                        Summary(current)
                        Counters(current)
                        Text(ConduitBundle.message("review.findings"), style = ConduitType.title)
                        val visible = current.findings.filter { it.status != FindingStatus.IGNORED }
                        if (visible.isEmpty()) {
                            Text(
                                ConduitBundle.message("review.findings.none"),
                                style = ConduitType.small,
                                color = ConduitColors.secondary,
                            )
                        }
                        visible.forEach { finding -> FindingCard(project, finding, finding.id in busy) }
                        val ignored = current.findings.count { it.status == FindingStatus.IGNORED }
                        if (ignored >
                            0
                        ) {
                            Text(
                                ConduitBundle.message("review.ignored", ignored),
                                style = ConduitType.tiny,
                                color = ConduitColors.secondary,
                            )
                        }
                        Checklist(project, current)
                    }
                }
            }
        }
        report?.let { Footer(project, it) }
    }
}

@Composable
private fun Summary(report: ReviewReport) {
    Card(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(ConduitColors.success), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, style = ConduitType.title)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ConduitBundle.message(if (report.completed) "review.completed" else "review.done"),
                        style = ConduitType.title,
                        modifier = Modifier.weight(1f),
                    )
                    Text(whenText(report.createdAt), style = ConduitType.tiny, color = ConduitColors.secondary)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        report.summary.ifBlank { ConduitBundle.message("review.summary.default", report.files.size) },
                        style = ConduitType.small,
                        color = ConduitColors.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    LineDelta(report.added, report.removed)
                }
                Text(
                    ConduitBundle.message("review.by", report.agentName, report.files.size),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                )
            }
        }
    }
}

private fun whenText(at: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    return if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
        ConduitBundle.message("review.today", time)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
    }
}

@Composable
private fun Counters(report: ReviewReport) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        Counter(
            report.count(FindingKind.ISSUE),
            ConduitBundle.message("review.count.ISSUE"),
            ConduitColors.error,
            AllIconsKeys.General.Error,
            Modifier.weight(1f),
        )
        Counter(
            report.count(FindingKind.SUGGESTION),
            ConduitBundle.message("review.count.SUGGESTION"),
            ConduitColors.warning,
            AllIconsKeys.General.Warning,
            Modifier.weight(1f),
        )
        Counter(
            report.count(FindingKind.OBSERVATION),
            ConduitBundle.message("review.count.OBSERVATION"),
            ConduitColors.purple,
            AllIconsKeys.General.Information,
            Modifier.weight(1f),
        )
        Counter(
            report.count(FindingKind.PRAISE),
            ConduitBundle.message("review.count.PRAISE"),
            ConduitColors.success,
            AllIconsKeys.Status.Success,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun Counter(
    count: Int,
    label: String,
    color: Color,
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    modifier: Modifier,
) {
    Card(modifier, padding = ConduitDimens.gap) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(ConduitColors.tint(color)),
                contentAlignment = Alignment.Center,
            ) { IdeIcon(icon) }
            Column {
                Text(count.toString(), style = ConduitType.title)
                Text(label, style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.HIGH -> ConduitColors.error
        Severity.MEDIUM -> ConduitColors.warning
        Severity.LOW -> ConduitColors.accent
    }

@Composable
private fun FindingCard(
    project: Project,
    finding: ReviewFinding,
    busy: Boolean,
) {
    val color = severityColor(finding.severity)
    val fixed = finding.status == FindingStatus.FIXED
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.card)
            .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.radius)),
    ) {
        Row(Modifier.fillMaxWidth().padding(ConduitDimens.pad), horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(ConduitColors.tint(color)), contentAlignment = Alignment.Center) {
                IdeIcon(
                    when (finding.severity) {
                        Severity.HIGH -> AllIconsKeys.General.Error
                        Severity.MEDIUM -> AllIconsKeys.General.Warning
                        Severity.LOW -> AllIconsKeys.General.Information
                    },
                    size = 14.dp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                    Text(
                        finding.title,
                        style = ConduitType.strong,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Badge(ConduitBundle.message("severity.${finding.severity.name}"), color)
                    if (fixed) Badge(ConduitBundle.message("review.fixed"), ConduitColors.success)
                    Spacer(Modifier.weight(1f))
                    finding.file?.let { file ->
                        val label = file.substringAfterLast('/') + (finding.line?.let { ":$it" } ?: "")
                        Text(
                            label,
                            style = ConduitType.tiny,
                            color = ConduitColors.secondary,
                            modifier = Modifier.clickable { ReviewActions.open(project, finding) }.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                    MoreMenu(ReviewActions.menu(project, finding), ConduitBundle.message("common.more"), key = AllIconsKeys.Actions.More)
                }
                Text(finding.description, style = ConduitType.small, color = ConduitColors.secondary)
                if (finding.recommendation.isNotBlank()) Text(finding.recommendation, style = ConduitType.small)
                finding.confidence?.let {
                    Text(
                        ConduitBundle.message("review.confidence", (it * PERCENT).toInt()),
                        style = ConduitType.tiny,
                        color = ConduitColors.secondary,
                    )
                }
                VSpace(ConduitDimens.tight)
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                    val primary = ReviewActions.primary(finding)
                    if (primary != null && !fixed) {
                        DefaultButton(onClick = { primary.run(project, finding) }, enabled = !busy) {
                            if (busy) CircularProgressIndicator(Modifier.size(12.dp)) else IconText(primary.icon, primary.label)
                        }
                    }
                    OutlinedButton(onClick = {
                        ReviewActions.ignore(project, finding)
                    }) { IconText(AllIconsKeys.General.Close, ConduitBundle.message("review.ignore")) }
                    Tooltip({ Text(ConduitBundle.message(if (finding.file != null) "review.diff.tooltip" else "review.diff.none")) }) {
                        OutlinedButton(onClick = {
                            ReviewActions.showDiff(project, finding)
                        }, enabled = finding.file != null) { Text(ConduitBundle.message("review.diff")) }
                    }
                }
            }
        }
    }
}

private const val PERCENT = 100

@Composable
private fun Checklist(
    project: Project,
    report: ReviewReport,
) {
    val service = ReviewService.getInstance(project)
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ConduitBundle.message("review.checklist"), style = ConduitType.strong, modifier = Modifier.weight(1f))
            Text(
                "${report.checklist.count { it.checked }}/${report.checklist.size}",
                style = ConduitType.small,
                color = ConduitColors.secondary,
            )
        }
        VSpace(ConduitDimens.tight)
        report.checklist.forEachIndexed { index, item ->
            Tooltip({ Text(item.note.ifBlank { item.label }) }) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
                ) {
                    Checkbox(checked = item.checked, onCheckedChange = { service.toggleChecklist(index) })
                    IdeIcon(AllIconsKeys.Nodes.Related, size = 14.dp)
                    Text(item.label, style = ConduitType.small)
                }
            }
        }
    }
}

@Composable
private fun Footer(
    project: Project,
    report: ReviewReport,
) {
    val service = ReviewService.getInstance(project)
    val publishable = service.canPublish()
    Row(
        Modifier.fillMaxWidth().padding(top = ConduitDimens.gap),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DefaultSplitButton(
            onClick = { if (publishable) ReviewActions.publish(project) else ReviewActions.copy(project) },
            secondaryOnClick = {},
            content = {
                IconText(
                    AllIconsKeys.General.User,
                    ConduitBundle.message(if (publishable) "review.publish" else "review.copyComment"),
                )
            },
            menuContent = {
                selectableItem(selected = false, onClick = { ReviewActions.copy(project) }) { Text(ConduitBundle.message("review.copy")) }
                selectableItem(selected = false, onClick = { ReviewActions.save(project) }) { Text(ConduitBundle.message("review.save")) }
            },
        )
        OutlinedButton(onClick = service::complete, enabled = !report.completed, modifier = Modifier.weight(1f)) {
            Text(ConduitBundle.message(if (report.completed) "review.completed" else "review.complete"), color = ConduitColors.success)
        }
        MoreMenu(
            listOf(
                MenuEntry(ConduitBundle.message("review.again")) { service.run(report.target) },
                MenuEntry(
                    ConduitBundle.message("review.reopenIgnored"),
                    enabled = report.findings.any { it.status == FindingStatus.IGNORED },
                ) {
                    report.findings.filter { it.status == FindingStatus.IGNORED }.forEach { service.reopen(it.id) }
                },
                MenuEntry(ConduitBundle.message("review.openFiles")) {
                    report.findings
                        .mapNotNull { it.file }
                        .distinct()
                        .forEach { ReviewActions.openPath(project, it) }
                },
            ),
            ConduitBundle.message("common.more"),
        )
    }
}
