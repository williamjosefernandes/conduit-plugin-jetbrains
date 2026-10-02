package dev.conduit.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedDiffFormatTest {
    private fun diff(
        path: String,
        from: String?,
        to: String?,
    ) = PendingDiff.create(DiffId(path), SessionId("s"), path, from, to)

    @Test
    fun `formatted diffs apply back to the original`() {
        val original = (1..12).joinToString("\n") { "line $it" } + "\n"
        val proposed = original.replace("line 2", "LINE 2").replace("line 11\n", "line 11\nline 11b\n")
        val text = UnifiedDiffFormat.format(diff("src/a.txt", original, proposed))

        val patch = UnifiedPatch.parse(text).single()
        assertEquals("src/a.txt" to "src/a.txt", patch.oldPath to patch.newPath)
        assertEquals(2, patch.hunks.size)
        assertEquals(proposed, UnifiedPatch.apply(original, patch))
    }

    @Test
    fun `creations deletions and renames are marked in the headers`() {
        assertEquals("--- /dev/null\n+++ b/n.txt\n@@ -1,0 +1,1 @@\n+new\n", UnifiedDiffFormat.format(diff("n.txt", null, "new")))
        assertEquals("--- a/d.txt\n+++ /dev/null\n@@ -1,1 +1,0 @@\n-old\n", UnifiedDiffFormat.format(diff("d.txt", "old", null)))
        val renamed = diff("a.txt", "x\n", "y\n").copy(newPath = "b.txt")
        assertEquals(
            "--- a/a.txt\n+++ b/b.txt\n",
            UnifiedDiffFormat
                .format(renamed)
                .lines()
                .take(2)
                .joinToString("\n", postfix = "\n"),
        )
    }
}
