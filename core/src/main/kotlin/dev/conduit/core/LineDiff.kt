package dev.conduit.core

/**
 * Line-based diff used to split a proposed write into independently acceptable [Hunk]s.
 * Pure Kotlin so the same hunks are produced in the IDE, in headless tools and in tests.
 */
object LineDiff {
    /** Above this many LCS cells the changed region is reported as one hunk instead of being refined. */
    private const val MAX_CELLS = 4_000_000L

    /** Splits [text] into lines the way [join] reverses: a trailing newline yields a last empty line. */
    fun lines(text: String): List<String> = text.split('\n')

    /** Inverse of [lines]. */
    fun join(lines: List<String>): String = lines.joinToString("\n")

    /**
     * Returns the hunks that turn [original] into [proposed], ordered by [Hunk.startLine] and never
     * overlapping. A null side means the file does not exist on that side (creation or deletion),
     * which is always a single hunk.
     */
    fun hunks(
        original: String?,
        proposed: String?,
    ): List<Hunk> {
        if (original == null || proposed == null) {
            val before = original?.let(::lines).orEmpty()
            val after = proposed?.let(::lines).orEmpty()
            return if (original == null && proposed == null) emptyList() else listOf(Hunk(0, before, after))
        }
        return diff(lines(original), lines(proposed))
    }

    /**
     * Applies the [HunkStatus.ACCEPTED] hunks to [original]; pending and rejected hunks keep the
     * original lines.
     */
    fun apply(
        original: String,
        hunks: List<Hunk>,
    ): String {
        val source = lines(original)
        val result = ArrayList<String>(source.size)
        var cursor = 0
        for (hunk in hunks.sortedBy { it.startLine }) {
            result.addAll(source.subList(cursor, hunk.startLine))
            result.addAll(if (hunk.status == HunkStatus.ACCEPTED) hunk.proposedLines else hunk.originalLines)
            cursor = hunk.startLine + hunk.originalLines.size
        }
        result.addAll(source.subList(cursor, source.size))
        return join(result)
    }

    private fun diff(
        a: List<String>,
        b: List<String>,
    ): List<Hunk> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        return when {
            midA.isEmpty() && midB.isEmpty() -> emptyList()
            midA.size.toLong() * midB.size > MAX_CELLS -> listOf(Hunk(prefix, midA.toList(), midB.toList()))
            else -> Refiner(midA, midB, prefix).run()
        }
    }

    /** Walks the LCS table of the changed region and groups consecutive edits into hunks. */
    private class Refiner(
        private val a: List<String>,
        private val b: List<String>,
        private val offset: Int,
    ) {
        private val width = b.size + 1
        private val lcs = lcsTable()
        private val hunks = ArrayList<Hunk>()
        private val removed = ArrayList<String>()
        private val added = ArrayList<String>()
        private var start = 0

        fun run(): List<Hunk> {
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                if (i < a.size && j < b.size && a[i] == b[j]) {
                    flush()
                    i++
                    j++
                    continue
                }
                if (removed.isEmpty() && added.isEmpty()) start = i
                val takeFromA = j == b.size || (i < a.size && lcs[(i + 1) * width + j] >= lcs[i * width + j + 1])
                if (takeFromA) removed += a[i++] else added += b[j++]
            }
            flush()
            return hunks
        }

        private fun flush() {
            if (removed.isEmpty() && added.isEmpty()) return
            hunks += Hunk(offset + start, removed.toList(), added.toList())
            removed.clear()
            added.clear()
        }

        /** Cell (i, j), stored at `i * width + j`, is the LCS length of the suffixes of a from i and of b from j. */
        private fun lcsTable(): IntArray {
            val table = IntArray((a.size + 1) * width)
            for (i in a.indices.reversed()) {
                for (j in b.indices.reversed()) {
                    table[i * width + j] =
                        if (a[i] == b[j]) {
                            table[(i + 1) * width + j + 1] + 1
                        } else {
                            maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
                        }
                }
            }
            return table
        }
    }
}
