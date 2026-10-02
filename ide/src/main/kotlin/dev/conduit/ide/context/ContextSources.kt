package dev.conduit.ide.context

import dev.conduit.agents.Attachment

/** The kinds of project information Conduit can send to an agent, in the order they are listed. */
enum class ContextSourceId {
    CURRENT_FILE,
    SELECTION,
    PROBLEMS,
    BRANCH,
    TESTS,
    COMMITS,
    RELATED,
    CONTEXT_FILES,
    OPEN_FILES,
    STRUCTURE,
    DOCUMENTATION,
    PROJECT_RULES,
    PACKS,
}

/**
 * One piece of text a source contributes.
 *
 * @property label shown under the message, e.g. `UserController.kt:12-16`
 * @property uri where it comes from, as the agent should refer to it
 */
data class ContextEntry(
    val label: String,
    val uri: String,
    val text: String,
) {
    fun toAttachment(): Attachment = Attachment.Text(uri, text)
}

/**
 * A source as the Context tab shows it.
 *
 * @property subtitle what it currently refers to, e.g. the file name or the branch
 * @property badge a count worth showing next to the title, e.g. the number of problems
 * @property entries what would be sent; empty when there is nothing to send right now
 * @property details lines shown when the source is expanded
 */
data class ContextSource(
    val id: ContextSourceId,
    val subtitle: String,
    val enabled: Boolean,
    val entries: List<ContextEntry>,
    val details: List<String> = emptyList(),
    val badge: Int? = null,
) {
    val available: Boolean get() = entries.isNotEmpty()
    val tokens: Int get() =
        entries.sumOf {
            dev.conduit.orchestrator.context.Tokens
                .estimate(it.text)
        }
    val included: Boolean get() = enabled && available
}

/**
 * Everything the Context tab shows at one moment.
 *
 * @property budget the most tokens the agent can take, as reported by the agent or configured
 */
data class ContextSnapshot(
    val sources: List<ContextSource> = emptyList(),
    val budget: Int,
    val computing: Boolean = false,
) {
    val included: List<ContextSource> get() = sources.filter { it.included }
    val usedTokens: Int get() = included.sumOf { it.tokens }
    val exceeded: Boolean get() = usedTokens > budget
}

/** Which sources a message takes, chosen in the composer. */
enum class ContextScope {
    /** Every source enabled in the Context tab. */
    PROJECT,

    /** Only the file in the editor, the selection and the files added explicitly. */
    CURRENT_FILE,

    /** Nothing but the message and its mentions. */
    NONE,
    ;

    fun admits(id: ContextSourceId): Boolean =
        when (this) {
            PROJECT -> true
            CURRENT_FILE -> id == ContextSourceId.CURRENT_FILE || id == ContextSourceId.SELECTION || id == ContextSourceId.CONTEXT_FILES
            NONE -> false
        }
}
