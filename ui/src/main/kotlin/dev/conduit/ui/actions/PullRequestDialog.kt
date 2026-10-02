package dev.conduit.ui.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.vcs.GhCli
import dev.conduit.ide.vcs.GitException
import dev.conduit.ui.chat.CodeBlockActions
import dev.conduit.ui.diff.DiffPresenterScope
import dev.conduit.ui.editorSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/**
 * The pull request the agent drafted, editable. Creating it uses the user's `gh` login and
 * publishes on GitHub, so it only happens when the user presses the button here.
 */
class PullRequestDialog(
    private val project: Project,
    title: String,
    body: String,
    private val base: String,
    private val branch: String,
) : DialogWrapper(project) {
    private val titleField = JBTextField(title)
    private val bodyArea =
        JBTextArea(body).apply {
            lineWrap = true
            wrapStyleWord = true
        }

    init {
        setTitle(ConduitBundle.message("pr.title"))
        setOKButtonText(ConduitBundle.message("pr.create"))
        isOKActionEnabled = GhCli.executable() != null
        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row { comment(ConduitBundle.message("pr.branches", branch, base)) }
            row(ConduitBundle.message("pr.field.title")) { cell(titleField).align(Align.FILL) }
            row {
                cell(
                    JBScrollPane(bodyArea).apply { preferredSize = editorSize(width = 620, height = 320) },
                ).align(Align.FILL)
            }.resizableRow()
            if (GhCli.executable() == null) row { comment(ConduitBundle.message("pr.noGh")) }
        }

    override fun createLeftSideActions(): Array<Action> =
        arrayOf(
            object : AbstractAction(ConduitBundle.message("pr.copy")) {
                override fun actionPerformed(e: ActionEvent) {
                    CodeBlockActions.copy("${titleField.text}\n\n${bodyArea.text}")
                    Notifications.info(project, ConduitBundle.message("pr.copied"))
                }
            },
        )

    override fun doOKAction() {
        val dir = project.basePath ?: return
        val title = titleField.text.trim()
        if (title.isEmpty()) {
            setErrorText(ConduitBundle.message("pr.titleRequired"), titleField)
            return
        }
        isOKActionEnabled = false
        project.getService(DiffPresenterScope::class.java).scope.launch {
            try {
                val url = GhCli.createPullRequest(dir, title, bodyArea.text, base)
                withContext(Dispatchers.EDT) {
                    close(OK_EXIT_CODE)
                    Notifications.info(project, ConduitBundle.message("pr.created", url))
                    if (url.startsWith("http")) BrowserUtil.browse(url)
                }
            } catch (e: GitException) {
                withContext(Dispatchers.EDT) {
                    isOKActionEnabled = true
                    Messages.showErrorDialog(project, e.message, ConduitBundle.message("pr.title"))
                }
            }
        }
    }
}
