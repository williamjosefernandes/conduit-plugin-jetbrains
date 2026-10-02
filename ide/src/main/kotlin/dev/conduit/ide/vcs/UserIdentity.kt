package dev.conduit.ide.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The user's name as git knows it (`user.name`), for the avatar next to their messages. */
@Service(Service.Level.PROJECT)
class UserIdentity(
    project: Project,
    scope: CoroutineScope,
) {
    private val nameState = MutableStateFlow(System.getProperty("user.name").orEmpty())

    val name: StateFlow<String> = nameState.asStateFlow()

    init {
        val dir = project.basePath
        if (dir != null) {
            scope.launch {
                runCatching { GitCli.run(dir, "config", "user.name").stdout.trim() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { nameState.value = it }
            }
        }
    }

    companion object {
        /** Up to two capital initials: `Rafael Souza` → `RS`, `willi` → `W`. */
        fun initials(name: String): String {
            val words = name.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
            val first = words.firstOrNull()?.take(1).orEmpty()
            val initials = if (words.size > 1) first + words.last().take(1) else first
            return initials.uppercase().ifEmpty { "?" }
        }

        fun getInstance(project: Project): UserIdentity = project.service()
    }
}
