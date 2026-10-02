package dev.conduit.orchestrator.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeBlocksTest {
    @Test
    fun `prose and fenced blocks alternate`() {
        val text = "Here is the fix:\n\n```kotlin title\nfun a() = 1\n```\n\nAnd a shell line:\n~~~\nls -la\n~~~\n"

        assertEquals(
            listOf(
                MessageSegment.Prose("Here is the fix:"),
                MessageSegment.Code("kotlin", "fun a() = 1", closed = true),
                MessageSegment.Prose("And a shell line:"),
                MessageSegment.Code("", "ls -la", closed = true),
            ),
            CodeBlocks.split(text),
        )
    }

    @Test
    fun `a streaming block is reported as open`() {
        assertEquals(
            listOf(MessageSegment.Prose("Start"), MessageSegment.Code("java", "class A {", closed = false)),
            CodeBlocks.split("Start\n```java\nclass A {"),
        )
    }

    @Test
    fun `nested shorter fences stay inside the block`() {
        val text = "````md\n```kotlin\nx\n```\n````\n"
        assertEquals(listOf(MessageSegment.Code("md", "```kotlin\nx\n```", closed = true)), CodeBlocks.split(text))
    }

    @Test
    fun `plain text is one prose segment`() {
        assertEquals(listOf(MessageSegment.Prose("just words")), CodeBlocks.split("just words"))
        assertEquals(emptyList(), CodeBlocks.split("   \n"))
    }
}
