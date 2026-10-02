package dev.conduit.orchestrator.context

import kotlin.test.Test
import kotlin.test.assertEquals

class MentionsTest {
    @Test
    fun `mentions are classified by shape`() {
        val prompt = "Look at @src/main/Foo.kt and @Bar.java, then @UserService.save (see @docs/) with @selection."
        val mentions = Mentions.parse(prompt)

        assertEquals(
            listOf(
                MentionKind.FILE to "src/main/Foo.kt",
                MentionKind.FILE to "Bar.java",
                MentionKind.SYMBOL to "UserService.save",
                MentionKind.FOLDER to "docs/",
                MentionKind.KEYWORD to "selection",
            ),
            mentions.map { it.kind to it.text },
        )
        assertEquals("@src/main/Foo.kt", prompt.substring(mentions[0].range))
        assertEquals("@selection", prompt.substring(mentions[4].range))
    }

    @Test
    fun `emails and lone at signs are not mentions`() {
        assertEquals(emptyList(), Mentions.parse("mail me at user@example.com or @ the door"))
        assertEquals(listOf("Foo"), Mentions.parse("@Foo").map { it.text })
    }

    @Test
    fun `strip leaves the names in place for the agent`() {
        assertEquals("Explain Foo.kt and UserService.", Mentions.strip("Explain @Foo.kt and @UserService."))
    }
}
