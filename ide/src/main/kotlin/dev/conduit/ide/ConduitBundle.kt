package dev.conduit.ide

import com.intellij.openapi.diagnostic.logger
import dev.conduit.ide.i18n.ConduitLanguage
import dev.conduit.ide.i18n.ConduitLanguageService
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

@NonNls
private const val BUNDLE = "messages.ConduitBundle"

/**
 * All user-visible text, in the language chosen in Conduit's settings (see [ConduitLanguageService]),
 * not the IDE's: `ConduitBundle.properties` is English, `ConduitBundle_pt_BR.properties` Brazilian
 * Portuguese. A key missing from a translation falls back to English.
 *
 * Plurals use `choice` patterns, e.g. `{0,choice,0#{0} files|1#1 file|1<{0} files}`.
 */
object ConduitBundle {
    private val bundle = LocalizedBundle(BUNDLE, ConduitBundle::class.java.classLoader)

    /** The message for [key] in the current language, formatted with [params]. */
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = bundle.format(ConduitLanguageService.current(), key, params)

    /** The message for [key] in the current language, formatted with [params] given as a list. */
    @Nls
    fun messageOf(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        params: List<Any>,
    ): String = bundle.format(ConduitLanguageService.current(), key, params.toTypedArray())

    /** The message for [key] in [language], formatted with [params]. Never fails: a missing key is logged. */
    @Nls
    fun message(
        language: ConduitLanguage,
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = bundle.format(language, key, params)

    /** Whether [key] exists, in English at least. */
    fun has(key: String): Boolean = bundle.has(key)
}

/**
 * The messages of the resource bundle [baseName] in each [ConduitLanguage]: the language's own file
 * first, then the English base file. Formatting follows [java.text.MessageFormat] in the language's
 * locale, so numbers read `1,5` in Portuguese.
 */
class LocalizedBundle(
    private val baseName: String,
    private val loader: ClassLoader,
) {
    private val bundles = ConcurrentHashMap<ConduitLanguage, ResourceBundle>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    @Nls
    fun message(
        language: ConduitLanguage,
        key: String,
        vararg params: Any,
    ): String = format(language, key, params)

    @Nls
    fun format(
        language: ConduitLanguage,
        key: String,
        params: Array<out Any>,
    ): String {
        val pattern = pattern(language, key) ?: return missing(key)
        return if (params.isEmpty()) pattern else MessageFormat(pattern, language.locale).format(params)
    }

    fun has(key: String): Boolean = pattern(ConduitLanguage.EN, key) != null

    /** The bundle of [language], whose parent is the English one. */
    fun bundle(language: ConduitLanguage): ResourceBundle =
        bundles.computeIfAbsent(language) {
            // No fallback to the JVM's default locale: English is chosen as English even on a Portuguese system.
            ResourceBundle.getBundle(
                baseName,
                language.locale,
                loader,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
            )
        }

    private fun pattern(
        language: ConduitLanguage,
        key: String,
    ): String? =
        try {
            bundle(language).getString(key)
        } catch (_: MissingResourceException) {
            null
        }

    /** Never shows the raw key: the last part of it, in words, reads better than `chat.send.button`. */
    private fun missing(key: String): String {
        if (reported.add(key)) logger<LocalizedBundle>().warn("Message '$key' is missing from $baseName")
        return key
            .substringAfterLast('.')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}
