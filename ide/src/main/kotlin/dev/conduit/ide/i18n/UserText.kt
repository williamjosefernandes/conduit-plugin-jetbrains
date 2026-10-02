package dev.conduit.ide.i18n

import dev.conduit.ide.ConduitBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

/**
 * Text kept in a state to be shown later. It is put into words when shown, so a change of
 * language reaches messages that are already on screen.
 */
fun interface UserText {
    @Nls
    fun text(): String

    companion object {
        fun message(
            @PropertyKey(resourceBundle = "messages.ConduitBundle") key: String,
            vararg params: Any,
        ): UserText = UserText { ConduitBundle.message(key, *params) }

        fun error(error: Throwable): UserText = UserText { ConduitErrors.describe(error) }

        /** Text that is not translated: a name, a path, or words from outside Conduit. */
        fun verbatim(text: String): UserText = UserText { text }
    }
}
