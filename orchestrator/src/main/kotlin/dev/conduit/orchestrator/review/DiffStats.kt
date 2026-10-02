package dev.conduit.orchestrator.review

import dev.conduit.core.FileChange
import dev.conduit.core.FileChangeStatus

/** Reads a unified diff (as `git diff` or `gh pr diff` print it) into per-file line counts. */
object DiffStats {
    private val header = Regex("""^diff --git a/(.+?) b/(.+)$""")

    /** The file being read, updated line by line. */
    private class Current(
        var path: String,
    ) {
        var status = FileChangeStatus.MODIFIED
        var added = 0
        var removed = 0
        var inHunk = false

        fun read(line: String) {
            when {
                line.startsWith("new file mode") -> status = FileChangeStatus.ADDED
                line.startsWith("deleted file mode") -> status = FileChangeStatus.DELETED
                line.startsWith("rename to ") -> path = line.removePrefix("rename to ").trim()
                line.startsWith("@@") -> inHunk = true
                inHunk && line.startsWith("+") -> added++
                inHunk && line.startsWith("-") -> removed++
                !inHunk && line.startsWith("+++ ") && line != "+++ /dev/null" -> path = line.removePrefix("+++ ").removePrefix("b/").trim()
            }
        }

        fun toChange() = FileChange(path, status, added, removed)
    }

    fun parse(diff: String): List<FileChange> {
        val files = ArrayList<FileChange>()
        var current: Current? = null
        for (line in diff.lineSequence()) {
            val match = header.find(line)
            if (match != null) {
                current?.let { files += it.toChange() }
                current = Current(match.groupValues[2])
            } else {
                current?.read(line)
            }
        }
        current?.let { files += it.toChange() }
        return files
    }
}
