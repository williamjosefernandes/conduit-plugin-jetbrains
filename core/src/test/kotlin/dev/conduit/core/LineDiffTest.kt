package dev.conduit.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineDiffTest {
    private fun diff(
        original: String?,
        proposed: String?,
    ) = PendingDiff.create(DiffId("d"), SessionId("s"), "f.txt", original, proposed)

    @Test
    fun `identical texts produce no hunks`() {
        assertTrue(LineDiff.hunks("a\nb\n", "a\nb\n").isEmpty())
    }

    @Test
    fun `separate edits become separate hunks`() {
        val hunks = LineDiff.hunks("a\nb\nc\nd\ne", "a\nB\nc\nd\nE\nf")
        assertEquals(
            listOf(Hunk(1, listOf("b"), listOf("B")), Hunk(4, listOf("e"), listOf("E", "f"))),
            hunks,
        )
    }

    @Test
    fun `insertion and deletion keep their original position`() {
        assertEquals(listOf(Hunk(1, emptyList(), listOf("x"))), LineDiff.hunks("a\nb", "a\nx\nb"))
        assertEquals(listOf(Hunk(1, listOf("b"), emptyList())), LineDiff.hunks("a\nb\nc", "a\nc"))
    }

    @Test
    fun `accepting every hunk yields the proposed text and rejecting yields the original`() {
        val original = "one\ntwo\nthree\nfour\n"
        val proposed = "zero\none\n2\nthree\n"
        val d = diff(original, proposed)
        assertEquals(proposed, d.withAllHunks(HunkStatus.ACCEPTED).resolvedContent())
        assertEquals(original, d.withAllHunks(HunkStatus.REJECTED).resolvedContent())
        assertEquals(original, d.resolvedContent())
    }

    @Test
    fun `partial acceptance applies only the accepted hunk`() {
        val d = diff("a\nb\nc\nd\ne", "a\nB\nc\nd\nE")
        assertEquals("a\nB\nc\nd\ne", d.withHunkStatus(0, HunkStatus.ACCEPTED).resolvedContent())
        assertEquals("a\nb\nc\nd\nE", d.withHunkStatus(1, HunkStatus.ACCEPTED).resolvedContent())
    }

    @Test
    fun `creation and deletion are single hunks`() {
        val created = diff(null, "x\ny")
        assertEquals(1, created.hunks.size)
        assertNull(created.resolvedContent())
        assertEquals("x\ny", created.withAllHunks(HunkStatus.ACCEPTED).resolvedContent())

        val deleted = diff("x\ny", null)
        assertEquals("x\ny", deleted.resolvedContent())
        assertNull(deleted.withAllHunks(HunkStatus.ACCEPTED).resolvedContent())
    }

    @Test
    fun `random edits always round trip`() {
        val random = Random(42)
        repeat(300) {
            val original = List(random.nextInt(0, 30)) { "l${random.nextInt(6)}" }
            val proposed = original.toMutableList()
            repeat(random.nextInt(0, 8)) {
                when (random.nextInt(3)) {
                    0 -> proposed.add(random.nextInt(proposed.size + 1), "n${random.nextInt(6)}")
                    1 -> if (proposed.isNotEmpty()) proposed.removeAt(random.nextInt(proposed.size))
                    else -> if (proposed.isNotEmpty()) proposed[random.nextInt(proposed.size)] = "c${random.nextInt(6)}"
                }
            }
            val a = LineDiff.join(original)
            val b = LineDiff.join(proposed)
            val d = diff(a, b)
            assertEquals(b, d.withAllHunks(HunkStatus.ACCEPTED).resolvedContent())
            assertEquals(a, d.withAllHunks(HunkStatus.REJECTED).resolvedContent())
            d.hunks.zipWithNext().forEach { (x, y) -> assertTrue(x.startLine + x.originalLines.size <= y.startLine) }
        }
    }
}
