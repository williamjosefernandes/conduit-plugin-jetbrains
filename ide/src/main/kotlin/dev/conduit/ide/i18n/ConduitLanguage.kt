package dev.conduit.ide.i18n

import com.intellij.DynamicBundle
import java.util.Locale

/**
 * A language Conduit's interface can be shown in. It is chosen in Conduit's own settings and is
 * independent of the IDE's language: it never changes the IDE itself.
 *
 * @property code what is stored in the settings
 * @property locale the resource bundle locale and the one numbers are formatted in
 * @property displayName the language's own name, as listed in the selector in every language
 * @property englishName how prompts name the language to agents
 */
enum class ConduitLanguage(
    val code: String,
    val locale: Locale,
    val displayName: String,
    val englishName: String,
) {
    PT_BR("pt_BR", Locale.forLanguageTag("pt-BR"), "Português (Brasil)", "Brazilian Portuguese"),
    EN("en", Locale.ENGLISH, "English", "English"),
    ;

    companion object {
        /** The stored language for [code] (`pt_BR`, `pt-BR` or `en`), or null when nothing valid is stored. */
        fun fromCode(code: String?): ConduitLanguage? {
            val normalized = code?.trim()?.replace('-', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.code.equals(normalized, ignoreCase = true) }
        }

        /** Portuguese of any region reads Brazilian Portuguese; every other language reads English. */
        fun forLocale(locale: Locale): ConduitLanguage = if (locale.language == "pt") PT_BR else EN

        /**
         * The language to use before the user chooses one: the IDE's when it has a language pack,
         * otherwise the operating system's.
         */
        fun detect(
            ide: Locale = ideLocale(),
            system: Locale = Locale.getDefault(),
        ): ConduitLanguage =
            when {
                ide.language == "pt" -> PT_BR
                // A language pack other than English: Conduit has no translation for it.
                ide.language.isNotEmpty() && ide.language != "en" -> EN
                else -> forLocale(system)
            }

        /** The stored choice when there is one; detection otherwise. */
        fun resolve(
            stored: String?,
            detected: () -> ConduitLanguage = { detect() },
        ): ConduitLanguage = fromCode(stored) ?: detected()

        private fun ideLocale(): Locale = runCatching { DynamicBundle.getLocale() }.getOrDefault(Locale.ENGLISH)
    }
}
