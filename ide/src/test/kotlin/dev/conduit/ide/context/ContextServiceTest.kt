package dev.conduit.ide.context

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.agents.Attachment
import dev.conduit.ide.settings.ConduitSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class ContextServiceTest : BasePlatformTestCase() {
    private val service get() = ContextService.getInstance(project)

    /** Runs [block] off the EDT while the test, which owns the EDT, keeps its events flowing. */
    private fun <T> pump(block: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.Default).async { block() }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!result.isCompleted && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(PUMP_MS)
        }
        assertTrue("timed out", result.isCompleted)
        return runBlocking { result.await() }
    }

    override fun tearDown() {
        try {
            service.restoreDefaults()
            service.clearFiles()
            service.state.pinnedFiles
                .toList()
                .forEach(service::unpin)
            service.setScope(ContextScope.PROJECT)
        } finally {
            super.tearDown()
        }
    }

    fun testTheEditorFileAndSelectionAreSentWithSecretsMasked() {
        myFixture.configureByText("Config.kt", "val name = \"demo\"\n<selection>val password = \"hunter2hunter\"</selection>\n")
        val entries = pump { service.entries(ContextScope.CURRENT_FILE) }
        val selection = entries.single { it.label.endsWith(":2-2") }
        assertFalse(selection.text, selection.text.contains("hunter2hunter"))
        assertTrue(entries.any { it.label == "Config.kt" })
        assertTrue(entries.none { it.label.contains("structure", ignoreCase = true) })
    }

    fun testDisabledSourcesAndTheNoneScopeSendNothing() {
        myFixture.configureByText("Main.kt", "fun main() = Unit\n")
        service.setEnabled(ContextSourceId.CURRENT_FILE, false)
        val entries = pump { service.entries(ContextScope.CURRENT_FILE) }
        assertTrue(entries.none { it.label == "Main.kt" })
        assertTrue(pump { service.entries(ContextScope.NONE) }.isEmpty())
        service.restoreDefaults()
        assertTrue(service.isEnabled(ContextSourceId.CURRENT_FILE))
    }

    fun testSensitiveAndIgnoredFilesAreNeverAdded() {
        assertTrue(service.isHidden(".env"))
        assertTrue(service.isHidden("certs/server.pem"))
        assertTrue(service.isHidden("build/classes/A.class"))
        assertFalse(service.isHidden("src/Main.kt"))
        assertEquals(0, service.addFiles(listOf(".env")))
        myFixture.addFileToProject("src/Util.kt", "object Util\n")
        assertEquals(1, service.addFiles(listOf("src/Util.kt")))
        service.pin("src/Util.kt")
        service.clearFiles()
        assertEquals(listOf("src/Util.kt"), service.contextFiles.value)
    }

    fun testAddedFilesAreAttached() {
        myFixture.addFileToProject("src/Util.kt", "object Util\n")
        service.addFiles(listOf("src/Util.kt"))
        val entry = pump { service.entries(ContextScope.PROJECT) }.single { it.label == "src/Util.kt" }
        assertEquals("object Util\n", (entry.toAttachment() as Attachment.Text).text)
        assertTrue(ConduitSettings.getInstance().state.maskSecrets)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val PUMP_MS = 10L
    }
}
