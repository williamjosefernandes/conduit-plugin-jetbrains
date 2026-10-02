package dev.conduit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileChangeTest {
    @Test
    fun `line counts of edits, creations and deletions`() {
        assertEquals(FileChange("a", FileChangeStatus.MODIFIED, 2, 1), FileChange.between("a", "x\ny\n", "x\nY\nz\n"))
        assertEquals(FileChange("a", FileChangeStatus.ADDED, 3, 0), FileChange.between("a", null, "1\n2\n3\n"))
        assertEquals(FileChange("a", FileChangeStatus.DELETED, 0, 2), FileChange.between("a", "1\n2", null))
        assertEquals(FileChange("a", FileChangeStatus.MODIFIED, 1, 0), FileChange.between("a", "", "x"))
        assertNull(FileChange.between("a", "same", "same"))
    }
}
