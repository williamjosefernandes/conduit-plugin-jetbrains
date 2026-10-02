package dev.conduit.ide.i18n

import com.intellij.openapi.diagnostic.logger
import dev.conduit.core.ErrorCode
import dev.conduit.core.Failure
import dev.conduit.core.HasFailure
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.tasks.TaskException
import dev.conduit.ide.vcs.GitException
import dev.conduit.orchestrator.chat.ChatItem
import org.jetbrains.annotations.Nls

/**
 * Puts failures into words for the user, in the language of the interface. Technical details
 * (exception messages, stack traces, CLI output) are never the main message: they go to the log,
 * or after the explanation when they help.
 */
object ConduitErrors {
    private val log = logger<ConduitErrors>()

    /** The sentence for [failure]. A missing name reads "the agent". */
    @Nls
    fun describe(failure: Failure): String {
        val key = "error.${failure.code.name}"
        val args =
            when (failure.code) {
                // The argument is an action kind: shown by its name in the settings.
                ErrorCode.POLICY_KIND_BLOCKED -> failure.args.map { ConduitBundle.message("settings.policy.kind.$it") }
                else -> failure.args
            }.ifEmpty { listOf(ConduitBundle.message("error.theAgent")) }
        return ConduitBundle.messageOf(key, args)
    }

    /** The message for [error]: Conduit's own wording when it knows the error, a generic one otherwise. */
    @Nls
    fun describe(error: Throwable): String =
        when (error) {
            is HasFailure -> describe(error.failure)
            // Thrown with a message already written for the user.
            is GitException, is TaskException, is UserFacingException -> error.message ?: unexpected(error)
            else -> unexpected(error)
        }

    /** What an error line of the chat says. */
    @Nls
    fun describe(notice: ChatItem.Notice): String = notice.failure?.let(::describe) ?: notice.text

    @Nls
    private fun unexpected(error: Throwable): String {
        log.info("Unexpected error shown to the user", error)
        val detail =
            error.message
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.take(MAX_DETAIL)
        return if (detail.isNullOrEmpty()) {
            ConduitBundle.message("error.unexpected")
        } else {
            ConduitBundle.message("error.unexpected.detail", detail)
        }
    }

    private const val MAX_DETAIL = 300
}

/** An exception whose message was written for the user, in the interface's language. */
open class UserFacingException(
    @Nls message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
