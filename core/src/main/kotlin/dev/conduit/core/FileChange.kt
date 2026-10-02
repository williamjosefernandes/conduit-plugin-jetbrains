package dev.conduit.core

import kotlinx.serialization.Serializable

/** How a file differs from its earlier version. */
@Serializable
enum class FileChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
}

/**
 * One file an agent changed, as shown in the "modified files" lists.
 *
 * @property path project-relative path, `/`-separated
 * @property added lines added
 * @property removed lines removed
 */
@Serializable
data class FileChange(
    val path: String,
    val status: FileChangeStatus,
    val added: Int,
    val removed: Int,
) {
    companion object {
        /**
         * Compares two versions of a file; null [before] means the file is new, null [after] that
         * it was deleted. Returns null when nothing changed.
         */
        fun between(
            path: String,
            before: String?,
            after: String?,
        ): FileChange? {
            if (before == after) return null
            val old = before?.let(::lines).orEmpty()
            val new = after?.let(::lines).orEmpty()
            val status =
                when {
                    before == null -> FileChangeStatus.ADDED
                    after == null -> FileChangeStatus.DELETED
                    else -> FileChangeStatus.MODIFIED
                }
            if (old.isEmpty() || new.isEmpty()) return FileChange(path, status, new.size, old.size)
            val hunks = LineDiff.hunks(LineDiff.join(old), LineDiff.join(new))
            return FileChange(path, status, hunks.sumOf { it.proposedLines.size }, hunks.sumOf { it.originalLines.size })
        }

        /** Lines of [text]; a final newline does not start a line of its own and an empty text has none. */
        private fun lines(text: String): List<String> {
            val normalized = text.replace("\r\n", "\n").removeSuffix("\n")
            return if (normalized.isEmpty()) emptyList() else LineDiff.lines(normalized)
        }
    }
}
