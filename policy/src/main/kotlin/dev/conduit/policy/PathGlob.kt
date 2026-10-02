package dev.conduit.policy

/**
 * Gitignore-flavoured glob matching on `/`-separated paths, independent of the host OS.
 *
 * - `*` matches within one path segment, `**` across segments, `?` one character.
 * - A pattern without `/` matches the file name anywhere in the tree (`.env`, `*.pem`).
 * - A pattern with `/` matches the path from any directory down (`config/secrets.yml`, or a
 *   directory followed by a double star for everything below it).
 */
class PathGlob(
    val pattern: String,
) {
    private val nameOnly = !pattern.contains('/')
    private val regex = Regex(translate(pattern), RegexOption.IGNORE_CASE)

    /** True when [path] (absolute or relative, either separator) matches. */
    fun matches(path: String): Boolean {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (nameOnly) return regex.matches(normalized.substringAfterLast('/'))
        val segments = normalized.split('/')
        return segments.indices.any { start -> regex.matches(segments.subList(start, segments.size).joinToString("/")) }
    }

    private fun translate(glob: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> {
                    out.append("(?:.*/)?")
                    i += 2
                }
                glob.startsWith("**", i) -> {
                    out.append(".*")
                    i += 1
                }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return out.toString()
    }
}
