package dev.conduit.orchestrator.context

/** What kind of thing an `@` mention names. */
enum class MentionKind {
    /** `@src/Main.kt`, `@Main.kt` */
    FILE,

    /** `@src/main/kotlin/` (trailing slash) */
    FOLDER,

    /** `@UserService`, `@UserService.save` */
    SYMBOL,

    /** `@selection`, `@problems`, `@branch`, `@terminal` and the other typed context keywords */
    KEYWORD,
}

/**
 * An `@` mention found in a prompt.
 *
 * @property text the mention without the `@`
 * @property range where it sits in the prompt, `@` included
 */
data class Mention(
    val kind: MentionKind,
    val text: String,
    val range: IntRange,
)

/**
 * Finds `@` mentions in a prompt. Mentions end at whitespace; a trailing punctuation mark that
 * cannot belong to a name (`,` `.` `;` `:` `!` `?` `)`) is left out, so "see @Foo." mentions `Foo`.
 */
object Mentions {
    /** Context keywords that are not files or symbols. */
    val KEYWORDS = setOf("selection", "file", "problems", "branch", "tests", "terminal", "diff", "commit")

    private val pattern = Regex("""(?<![\w@])@([^\s@]+)""")
    private val trailing = setOf(',', '.', ';', ':', '!', '?', ')', '\'', '"')

    /** A name ending in one of these is a file even without a path. */
    private val fileExtensions =
        setOf(
            "kt",
            "kts",
            "java",
            "groovy",
            "scala",
            "js",
            "jsx",
            "ts",
            "tsx",
            "mjs",
            "cjs",
            "vue",
            "svelte",
            "py",
            "rb",
            "go",
            "rs",
            "php",
            "cs",
            "fs",
            "swift",
            "m",
            "mm",
            "c",
            "cc",
            "cpp",
            "h",
            "hpp",
            "dart",
            "gradle",
            "xml",
            "json",
            "yaml",
            "yml",
            "toml",
            "properties",
            "ini",
            "env",
            "md",
            "txt",
            "rst",
            "adoc",
            "html",
            "htm",
            "css",
            "scss",
            "sass",
            "less",
            "sql",
            "sh",
            "bash",
            "zsh",
            "bat",
            "ps1",
            "cmd",
            "proto",
            "graphql",
            "gql",
            "http",
            "csv",
            "lock",
            "conf",
            "cfg",
            "tf",
            "dockerfile",
        )

    fun parse(prompt: String): List<Mention> =
        pattern
            .findAll(prompt)
            .mapNotNull { match ->
                val raw = match.groupValues[1].trimEnd { it in trailing }
                if (raw.isEmpty()) return@mapNotNull null
                val range = match.range.first..(match.range.first + raw.length)
                Mention(kindOf(raw), raw, range)
            }.toList()

    /** The prompt with every mention replaced by its plain text, for the agent to read. */
    fun strip(prompt: String): String = pattern.replace(prompt) { it.groupValues[1] }

    private fun kindOf(text: String): MentionKind =
        when {
            text.lowercase() in KEYWORDS -> MentionKind.KEYWORD
            text.endsWith("/") || text.endsWith("\\") -> MentionKind.FOLDER
            text.contains('/') || text.contains('\\') -> MentionKind.FILE
            text.substringAfterLast('.', "").lowercase() in fileExtensions -> MentionKind.FILE
            else -> MentionKind.SYMBOL
        }
}
