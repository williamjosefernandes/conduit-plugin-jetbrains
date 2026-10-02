package dev.conduit.plugin

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.ActionKind
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.PolicyDecision
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ui.settings.ConduitConfigurable
import dev.conduit.ui.settings.GeneralPage
import dev.conduit.ui.settings.SettingsDraft
import java.util.Properties

class ConduitPluginTest : BasePlatformTestCase() {
    fun testSettingsPagesAreRegisteredUnderToolsConduit() {
        val pages = Configurable.APPLICATION_CONFIGURABLE.extensionList.filter { it.id?.startsWith("dev.conduit.settings") == true }
        val root = pages.single { it.id == ConduitConfigurable.ID }
        assertEquals("tools", root.parentId)
        assertEquals("Conduit", assertInstanceOf(root.createConfigurable(), ConduitConfigurable::class.java).displayName)
        assertTrue("the root page creates its pages, so their names follow Conduit's language", root.dynamic)
        val children = ConduitConfigurable().configurables.map { (it as SearchableConfigurable).id }.toSet()
        assertEquals(ConduitConfigurable.PAGES.map { it.first }.toSet(), children)
    }

    fun testGeneralPageTracksAndPersistsTheSettings() {
        val settings = ConduitSettings.getInstance()
        val page = GeneralPage()
        try {
            page.reset()
            assertFalse(page.isModified)
            assertEquals(ConduitSettings.DEFAULT_AGENT_ID, settings.state.defaultAgentId)
            assertEquals(AutonomyLevel.ASK_EVERY_ACTION, settings.state.autonomy)

            SettingsDraft.update {
                copy(
                    defaultAgent = "codex",
                    permissions =
                        permissions + (ActionKind.NETWORK to PolicyDecision.ASK_ALWAYS),
                )
            }
            assertTrue(page.isModified)
            page.apply()
            assertFalse(page.isModified)
            assertEquals("codex", settings.state.defaultAgentId)
            assertEquals(PolicyDecision.ASK_ALWAYS, settings.decision(ActionKind.NETWORK))

            SettingsDraft.update { copy(defaultAgent = "gemini") }
            page.reset()
            assertEquals("codex", SettingsDraft.current().defaultAgent)
        } finally {
            settings.state.defaultAgentId = ConduitSettings.DEFAULT_AGENT_ID
            settings.state.permissions = mutableMapOf()
            page.reset()
        }
    }

    fun testSafeDefaults() {
        val settings = ConduitSettings.getInstance()
        assertEquals(PolicyDecision.REVIEW_DIFF, settings.decision(ActionKind.EDIT_FILE))
        assertEquals(PolicyDecision.DENY, settings.decision(ActionKind.GIT_PUSH))
        assertEquals(PolicyDecision.DENY, settings.decision(ActionKind.DB_WRITE))
        assertTrue(settings.policyRules().excludedGlobs.contains(".env"))
    }

    fun testEveryLocaleDefinesTheDefaultBundleKeys() {
        val expected = bundleKeys("")
        assertFalse(expected.isEmpty())
        AutonomyLevel.entries.forEach { assertTrue("autonomy.${it.name}" in expected) }
        assertEquals("pt_BR", expected, bundleKeys("_pt_BR"))
    }

    private fun bundleKeys(suffix: String): Set<String> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/messages/ConduitBundle$suffix.properties"))
        return stream.reader().use { reader -> Properties().apply { load(reader) }.stringPropertyNames() }
    }
}
