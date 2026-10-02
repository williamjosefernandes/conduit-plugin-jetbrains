package dev.conduit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.conduit.ide.review.ReviewHighlighter
import dev.conduit.ide.review.ReviewService
import dev.conduit.ide.tasks.TaskService

/**
 * Restores what must not wait for the tool window: queued tasks resume, and the findings of the
 * last review are marked in the editor again. The context sources start with the tool window.
 */
class ConduitStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        TaskService.getInstance(project).start()
        ReviewService.getInstance(project).start()
        ReviewHighlighter.getInstance(project).start()
    }
}
