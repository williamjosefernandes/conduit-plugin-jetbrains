package dev.conduit.core

/** A patch that could not be parsed or does not apply to the content it was given. */
class PatchException(
    message: String,
) : Exception(message)

/**
 * Parses and applies unified diffs (the format of `git diff`), so an agent can propose a
 * multi-file change in one tool call. Pure Kotlin: nothing here touches the disk.
 */
object UnifiedPatch {
    /**
     * The changes to one file. A null [oldPath] means the file is created, a null [newPath]
     * that it is deleted; different non-null paths mean it is renamed.
     */
    data class FilePatch(
        val oldPath: String?,
        val newPath: String?,
        val hunks: List<Hunk>,
    )

    /**
     * @property oldStart 1-based line in the original where the hunk claims to start
     * @property before the lines the hunk expects to find (context and removals)
     * @property after the lines that replace them (context and additions)
     */
    data class Hunk(
        val oldStart: Int,
        val before: List<String>,
        val after: List<String>,
    )

    private val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@""")

    /**
     * Parses [text] into per-file patches. Lines before the first file header (commit messages,
     * `diff --git`, `index`) are ignored.
     *
     * @throws PatchException if no file patch is found or a hunk appears before a file header
     */
    fun parse(text: String): List<FilePatch> {
        val files = ArrayList<FilePatch>()
        var oldPath: String? = null
        var header = false
        var hunks = ArrayList<Hunk>()
        var builder: HunkBuilder? = null
        var newPath: String? = null

        fun flush() {
            builder?.let { hunks += it.build() }
            builder = null
            if (header) files += FilePatch(oldPath, newPath, hunks)
            hunks = ArrayList()
            header = false
        }

        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            val match = hunkHeader.find(line)
            when {
                line.startsWith("--- ") && next != null && next.startsWith("+++ ") -> {
                    flush()
                    oldPath = path(line)
                    newPath = path(next)
                    header = true
                    i++
                }
                match != null -> {
                    if (!header) throw PatchException("Hunk without a file header at line ${i + 1}")
                    builder?.let { hunks += it.build() }
                    builder = HunkBuilder(match.groupValues[1].toInt())
                }
                else -> builder?.add(line)
            }
            i++
        }
        flush()
        if (files.isEmpty()) throw PatchException("No file patch found; expected unified diff with ---/+++ headers")
        return files
    }

    /**
     * Applies [patch] to [original] (null when the file does not exist yet) and returns the new
     * content, or null when the patch deletes the file. Each hunk is applied where it says, or
     * at the nearest place where its context matches.
     *
     * @throws PatchException if a hunk's context is not found
     */
    fun apply(
        original: String?,
        patch: FilePatch,
    ): String? {
        if (patch.newPath == null) return null
        val lines = original?.let(LineDiff::lines)?.toMutableList() ?: ArrayList()
        // A new file starts as no lines at all, not as one empty line.
        if (original == null || original.isEmpty()) lines.clear()
        var offset = 0
        patch.hunks.forEachIndexed { index, hunk ->
            val expected = (hunk.oldStart - 1 + offset).coerceAtLeast(0)
            val at = locate(lines, hunk.before, expected) ?: throw PatchException("Hunk ${index + 1} does not apply to ${patch.newPath}")
            repeat(hunk.before.size) { lines.removeAt(at) }
            lines.addAll(at, hunk.after)
            offset += hunk.after.size - hunk.before.size
        }
        val result = LineDiff.join(lines)
        return if (original == null && lines.isNotEmpty() && !result.endsWith("\n")) result + "\n" else result
    }

    /** The index closest to [expected] where [block] occurs in [lines]; null when it does not occur. */
    private fun locate(
        lines: List<String>,
        block: List<String>,
        expected: Int,
    ): Int? {
        if (block.isEmpty()) return expected.coerceAtMost(lines.size)
        val last = lines.size - block.size
        if (last < 0) return null
        return (0..last)
            .sortedBy { kotlin.math.abs(it - expected) }
            .firstOrNull { start -> block.indices.all { lines[start + it] == block[it] } }
    }

    private fun path(headerLine: String): String? {
        val raw = headerLine.substring(HEADER_PREFIX).substringBefore('\t').trim()
        return when {
            raw == "/dev/null" -> null
            raw.startsWith("a/") || raw.startsWith("b/") -> raw.substring(2)
            else -> raw
        }
    }

    private class HunkBuilder(
        private val oldStart: Int,
    ) {
        private val before = ArrayList<String>()
        private val after = ArrayList<String>()

        fun add(line: String) {
            when (line.firstOrNull()) {
                ' ' -> {
                    before += line.substring(1)
                    after += line.substring(1)
                }
                '-' -> before += line.substring(1)
                '+' -> after += line.substring(1)
                // "\ No newline at end of file", blank separators and trailers carry no content.
                else -> Unit
            }
        }

        fun build() = Hunk(oldStart, before.toList(), after.toList())
    }

    private const val HEADER_PREFIX = 4
}
