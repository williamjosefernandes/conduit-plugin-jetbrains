package dev.conduit.ide.session

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndWait
import dev.conduit.core.AgentId
import dev.conduit.core.Decision
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.mcp.ide.ConduitMcpService
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.orchestrator.chat.ChatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * The Phase 1 acceptance scenario, run for real: "rename the class X and fix its usages" makes
 * the agent call `rename_symbol` on the IDE's MCP server, the change is reviewed, and the
 * project ends up refactored.
 *
 * Needs a logged-in CLI, so it only runs when `CONDUIT_LIVE_AGENT` names an agent id
 * (e.g. `claude-code`); otherwise it passes without doing anything. It spends a few cents of
 * the user's agent subscription.
 */
class LiveAgentAcceptanceTest : HeavyPlatformTestCase() {
    private val agentId: String? = System.getenv("CONDUIT_LIVE_AGENT")

    override fun runInDispatchThread(): Boolean = false

    override fun tearDown() {
        try {
            ConduitMcpService.getInstance().dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun file(path: String): String? = runReadAction { ProjectFiles.find(project, path)?.let(ProjectFiles::text) }

    fun testRenameThroughTheIdeToolIsReviewedAndApplied() {
        val agent = agentId ?: return
        val base = File(project.basePath!!)
        val src = File(base, "src/shop").apply { mkdirs() }
        File(src, "Greeter.java").writeText(
            """
            package shop;

            public class Greeter {
                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
            """.trimIndent() + "\n",
        )
        File(src, "Checkout.java").writeText(
            """
            package shop;

            public class Checkout {
                private final Greeter greeter = new Greeter();

                public String welcome(String customer) {
                    return greeter.greet(customer);
                }
            }
            """.trimIndent() + "\n",
        )
        val srcDir = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(base, "src")))
        runInEdtAndWait { PsiTestUtil.addSourceRoot(module, srcDir) }
        VfsUtil.markDirtyAndRefresh(false, true, true, srcDir)

        runBlocking(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) {
                val chat = ChatSessionManager.getInstance(project).open(AgentId(agent))
                val reviews = PendingChangesService.getInstance(project)
                chat.send(
                    "Rename the class Greeter (src/shop/Greeter.java) to Welcomer and update all usages. " +
                        "Use the conduit MCP server's rename_symbol tool for this; do not edit files by hand, and run no commands.",
                )
                while (chat.state.value.running) {
                    chat.state.value.pendingApprovals
                        .forEach { chat.decide(it.requestId, Decision.ALLOW_ONCE) }
                    reviews.reviews.value.forEach { reviews.acceptAll(it.id) }
                    delay(POLL_MS)
                }
                val state = chat.state.value
                state.items.forEach { println("  $it".take(LOG_CHARS)) }
                chat.close()

                val tools = state.items.filterIsInstance<ChatItem.ToolCall>()
                assertTrue("the agent used rename_symbol: $tools", tools.any { it.title.contains("rename_symbol") })
                assertTrue("no error notices: ${state.items}", state.items.none { it is ChatItem.Notice && it.error })
            }
        }

        VfsUtil.markDirtyAndRefresh(false, true, true, srcDir)
        assertNull(file("src/shop/Greeter.java"))
        assertTrue(file("src/shop/Welcomer.java")!!.contains("public class Welcomer"))
        assertTrue(file("src/shop/Checkout.java")!!.contains("new Welcomer()"))
    }

    private companion object {
        const val TIMEOUT_MS = 600_000L
        const val POLL_MS = 250L
        const val LOG_CHARS = 300
    }
}
