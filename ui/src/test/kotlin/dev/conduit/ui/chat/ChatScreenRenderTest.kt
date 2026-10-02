@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package dev.conduit.ui.chat

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.ProposedChange
import dev.conduit.core.Risk
import dev.conduit.core.SessionId
import dev.conduit.orchestrator.chat.ChatState
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the chat screen off-screen with the real Compose runtime and Jewel bridge theme,
 * and writes it to `build/screenshots/` so a person can look at it. Fails if the screen cannot
 * be composed at all, which is the regression this guards against.
 */
class ChatScreenRenderTest : BasePlatformTestCase() {
    fun testChatScreenComposesAndRendersToAnImage() {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f))
        try {
            scene.setContent {
                SwingBridgeTheme {
                    ChatScreen(project)
                }
            }
            val image = scene.render()
            val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.bytes
            val out = File("build/screenshots").apply { mkdirs() }.resolve("chat-empty.png")
            out.writeBytes(bytes)
            assertTrue(bytes.size > MIN_PNG_BYTES)
            println("Screenshot: ${out.absolutePath}")
        } finally {
            scene.close()
        }
    }

    fun testConversationItemsRender() {
        val id = SessionId("s")
        var state = ChatState().userSaid("Rename Greeter to Welcomer", listOf("Greeter.java"))
        listOf(
            AgentEvent.Thinking(id, "The user wants a rename; the IDE tool is the right call."),
            AgentEvent.ToolCall(id, "t1", "search", Risk.READ, title = "find_symbol Greeter"),
            AgentEvent.ToolResult(id, "t1", "class Greeter — shop/Greeter.java:3"),
            AgentEvent.ApprovalRequest(
                id,
                "r1",
                ActionKind.EDIT_FILE,
                "Edit Greeter.java",
                changes = listOf(ProposedChange("shop/Greeter.java", "Greeter", "Welcomer")),
            ),
            AgentEvent.Text(id, "I renamed the class with `rename_symbol`.\n\n```java\npublic class Welcomer {}\n```\n\nDone."),
            AgentEvent.Cost(id, usd = 0.12, contextUsed = 40_000, contextSize = 200_000),
        ).forEach { state = state.reduce(it) }
        state = state.question("q1", "Which test framework?", listOf("JUnit 5", "Kotest")).notice("Agent exited", error = true)
        render("chat-conversation.png") { TranscriptPreview(project, state) }
    }

    private fun render(
        name: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f))
        try {
            scene.setContent { SwingBridgeTheme { content() } }
            // Markdown is processed off the UI thread; give it a moment and render a second frame.
            scene.render()
            Thread.sleep(SETTLE_MS)
            val bytes =
                checkNotNull(
                    scene.render(SETTLE_MS * NANOS_PER_MS).encodeToData(EncodedImageFormat.PNG),
                ) { "PNG encoding failed" }.bytes
            val out = File("build/screenshots").apply { mkdirs() }.resolve(name)
            out.writeBytes(bytes)
            assertTrue(bytes.size > MIN_PNG_BYTES)
            println("Screenshot: ${out.absolutePath}")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 640
        const val MIN_PNG_BYTES = 2_000
        const val SETTLE_MS = 1_500L
        const val NANOS_PER_MS = 1_000_000L
    }
}
