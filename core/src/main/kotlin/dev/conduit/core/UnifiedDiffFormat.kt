package dev.conduit.core

/** Renders [PendingDiff]s as unified diff text, the format agents and `git` read. */
object UnifiedDiffFormat {
    private const val CONTEXT_LINES = 3

    fun format(diffs: List<PendingDiff>): String = diffs.joinToString("") { format(it) }

    fun format(diff: PendingDiff): String {
        val oldLines = diff.originalContent?.let(LineDiff::lines).orEmpty()
        val newLines = diff.proposedContent?.let(LineDiff::lines).orEmpty()
        val out = StringBuilder()
        out.append("--- ").append(if (diff.originalContent == null) "/dev/null" else "a/${diff.path}").append('\n')
        out.append("+++ ").append(if (diff.proposedContent == null) "/dev/null" else "b/${diff.newPath ?: diff.path}").append('\n')
        var shift = 0
        for (hunk in diff.hunks) {
            val from = (hunk.startLine - CONTEXT_LINES).coerceAtLeast(0)
            val to = (hunk.startLine + hunk.originalLines.size + CONTEXT_LINES).coerceAtMost(oldLines.size)
            val before = oldLines.subList(from, hunk.startLine)
            val after = oldLines.subList(hunk.startLine + hunk.originalLines.size, to)
            val oldCount = before.size + hunk.originalLines.size + after.size
            val newCount = before.size + hunk.proposedLines.size + after.size
            out.append("@@ -${from + 1},$oldCount +${from + 1 + shift},$newCount @@\n")
            before.forEach { out.append(' ').append(it).append('\n') }
            hunk.originalLines.forEach { out.append('-').append(it).append('\n') }
            hunk.proposedLines.forEach { out.append('+').append(it).append('\n') }
            after.forEach { out.append(' ').append(it).append('\n') }
            shift += hunk.proposedLines.size - hunk.originalLines.size
        }
        if (diff.hunks.isEmpty() && newLines != oldLines) out.append("@@ -0,0 +0,0 @@\n")
        return out.toString()
    }
}
