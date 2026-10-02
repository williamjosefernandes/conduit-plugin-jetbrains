package dev.conduit.ui

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.ConduitBundle"

/** All user-visible text. Translations: en (default), pt-BR, es. */
object ConduitBundle {
    private val instance = DynamicBundle(ConduitBundle::class.java, BUNDLE)

    /** Returns the localized message for [key], formatted with [params]. */
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = instance.getMessage(key, *params)
}
