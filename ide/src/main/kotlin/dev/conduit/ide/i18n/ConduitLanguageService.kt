package dev.conduit.ide.i18n

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import dev.conduit.ide.settings.ConduitSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Told when the language of Conduit's interface changes. Only the presentation changes: no data is touched. */
fun interface ConduitLanguageListener {
    fun languageChanged(language: ConduitLanguage)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<ConduitLanguageListener> = Topic(ConduitLanguageListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * The language of Conduit's interface: the user's choice in the settings, or the detected one
 * until they choose. Changing it notifies [ConduitLanguageListener.TOPIC]; text read through
 * [dev.conduit.ide.ConduitBundle] follows it immediately.
 */
@Service(Service.Level.APP)
class ConduitLanguageService {
    private val state = MutableStateFlow(ConduitLanguage.resolve(ConduitSettings.getInstance().state.language))

    val language: StateFlow<ConduitLanguage> = state.asStateFlow()

    init {
        active = state.value
    }

    /** Whether the user picked a language, as opposed to Conduit detecting it. */
    val isChosen: Boolean get() = ConduitLanguage.fromCode(ConduitSettings.getInstance().state.language) != null

    /** Stores [language] as the user's choice and switches the interface to it. */
    fun select(language: ConduitLanguage) {
        ConduitSettings.getInstance().state.language = language.code
        show(language)
    }

    /** Follows the stored setting again, after something other than [select] replaced it (Settings Sync, a reset). */
    fun reload() {
        show(ConduitLanguage.resolve(ConduitSettings.getInstance().state.language))
    }

    private fun show(language: ConduitLanguage) {
        val previous = state.getAndUpdate { language }
        active = language
        if (previous != language) {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(ConduitLanguageListener.TOPIC)
                .languageChanged(language)
        }
    }

    companion object {
        @Volatile
        private var active: ConduitLanguage? = null

        /**
         * Called each time text is looked up, so a UI toolkit can record that what it is drawing
         * depends on the language (Compose reads a snapshot state here and redraws on a change).
         */
        @Volatile
        var lookupObserver: (() -> Unit)? = null

        /** The language text is shown in right now; works before the application exists, using detection. */
        fun current(): ConduitLanguage {
            lookupObserver?.invoke()
            active?.let { return it }
            val application = ApplicationManager.getApplication()
            return if (application == null || application.isDisposed) ConduitLanguage.detect() else getInstance().language.value
        }

        fun getInstance(): ConduitLanguageService = service()
    }
}
