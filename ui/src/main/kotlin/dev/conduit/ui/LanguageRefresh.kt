package dev.conduit.ui

import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import dev.conduit.ide.i18n.ConduitLanguageListener
import dev.conduit.ide.i18n.ConduitLanguageService
import dev.conduit.ui.status.ConduitStatusBarWidgetFactory

/**
 * Makes what Conduit draws follow a change of language without rebuilding it, so nothing on screen
 * loses its state (typed text, scroll position, open sections) and no session is touched.
 *
 * Every lookup of text reads a Compose snapshot state, so Compose records which parts of the UI
 * depend on the language and redraws exactly those when it changes. Swing pieces that cache their
 * text are refreshed here; action texts are read anew each time an action is shown.
 */
@Service(Service.Level.APP)
class LanguageRefresh : Disposable {
    private val language = mutableStateOf(ConduitLanguageService.getInstance().language.value)

    init {
        ConduitLanguageService.lookupObserver = { language.value }
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                ConduitLanguageListener.TOPIC,
                ConduitLanguageListener { changed ->
                    ApplicationManager.getApplication().invokeLater {
                        language.value = changed
                        refreshStatusBars()
                    }
                },
            )
    }

    private fun refreshStatusBars() {
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ConduitStatusBarWidgetFactory.ID)
        }
    }

    override fun dispose() {
        ConduitLanguageService.lookupObserver = null
    }

    companion object {
        /** Starts following the language; called before Conduit draws anything. Cheap after the first call. */
        fun install() {
            service<LanguageRefresh>()
        }
    }
}
