package dev.conduit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UnifiedPatchTest {
    private val original = "one\ntwo\nthree\nfour\nfive\n"

    @Test
    fun `git style patch with several hunks applies`() {
        val patch =
            UnifiedPatch
                .parse(
                    """
                    diff --git a/src/A.txt b/src/A.txt
                    index 111..222 100644
                    --- a/src/A.txt
                    +++ b/src/A.txt
                    @@ -1,3 +1,3 @@
                     one
                    -two
                    +TWO
                     three
                    @@ -4,2 +4,3 @@
                     four
                     five
                    +six
                    """.trimIndent(),
                ).single()

        assertEquals("src/A.txt" to "src/A.txt", patch.oldPath to patch.newPath)
        assertEquals(2, patch.hunks.size)
        assertEquals("one\nTWO\nthree\nfour\nfive\nsix\n", UnifiedPatch.apply(original, patch))
    }

    @Test
    fun `hunks apply where the context matches even when line numbers are off`() {
        val patch = UnifiedPatch.parse("--- a/A.txt\n+++ b/A.txt\n@@ -40,2 +40,2 @@\n three\n-four\n+4\n").single()
        assertEquals("one\ntwo\nthree\n4\nfive\n", UnifiedPatch.apply(original, patch))
    }

    @Test
    fun `mismatching context is refused`() {
        val patch = UnifiedPatch.parse("--- a/A.txt\n+++ b/A.txt\n@@ -1,2 +1,2 @@\n one\n-TWO\n+2\n").single()
        assertFailsWith<PatchException> { UnifiedPatch.apply(original, patch) }
    }

    @Test
    fun `creation deletion and rename are told apart`() {
        val patches =
            UnifiedPatch.parse(
                """
                --- /dev/null
                +++ b/New.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                --- a/Old.txt
                +++ /dev/null
                @@ -1,1 +0,0 @@
                -bye
                --- a/Before.txt
                +++ b/After.txt
                @@ -1,1 +1,1 @@
                -x
                +y
                """.trimIndent(),
            )

        assertEquals(listOf(null, "Old.txt", "Before.txt"), patches.map { it.oldPath })
        assertEquals(listOf("New.txt", null, "After.txt"), patches.map { it.newPath })
        assertEquals("hello\nworld\n", UnifiedPatch.apply(null, patches[0]))
        assertNull(UnifiedPatch.apply("bye\n", patches[1]))
        assertEquals("y\n", UnifiedPatch.apply("x\n", patches[2]))
    }

    @Test
    fun `windows line endings and the no newline marker are tolerated`() {
        val patch =
            UnifiedPatch
                .parse(
                    "--- a/A.txt\r\n+++ b/A.txt\r\n@@ -1,1 +1,1 @@\r\n-one\r\n+1\r\n\\ No newline at end of file\r\n",
                ).single()
        assertEquals("1\ntwo\nthree\nfour\nfive\n", UnifiedPatch.apply(original, patch))
    }

    @Test
    fun `text without file headers is not a patch`() {
        assertFailsWith<PatchException> { UnifiedPatch.parse("just some text") }
        assertFailsWith<PatchException> { UnifiedPatch.parse("@@ -1,1 +1,1 @@\n-a\n+b\n") }
    }
}
