package dev.conduit.plugin

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.AutonomyLevel
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ui.settings.ConduitConfigurable
import java.util.Properties

class ConduitPluginTest : BasePlatformTestCase() {
    fun testSettingsPageIsRegisteredUnderTools() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList.single { it.id == "dev.conduit.settings" }

        assertEquals("tools", ep.parentId)
        assertEquals("settings.displayName", ep.key)
        val configurable = assertInstanceOf(ep.createConfigurable(), ConduitConfigurable::class.java)
        assertEquals("Conduit", configurable.displayName)
    }

    fun testSettingsPanelTracksPersistedState() {
        val state = ConduitSettings.getInstance().state
        val configurable = ConduitConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            assertFalse(configurable.isModified)
            assertEquals(ConduitSettings.DEFAULT_AGENT_ID, state.defaultAgentId)
            assertEquals(AutonomyLevel.ASK_EVERY_ACTION, state.autonomy)
            assertFalse(state.telemetryOptIn)

            state.defaultAgentId = "codex"
            assertTrue(configurable.isModified)
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            state.defaultAgentId = ConduitSettings.DEFAULT_AGENT_ID
        }
    }

    fun testEveryLocaleDefinesTheDefaultBundleKeys() {
        val expected = bundleKeys("")
        assertFalse(expected.isEmpty())
        AutonomyLevel.entries.forEach { assertTrue("autonomy.${it.name}" in expected) }
        listOf("_pt_BR", "_es").forEach { assertEquals("locale $it", expected, bundleKeys(it)) }
    }

    private fun bundleKeys(suffix: String): Set<String> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/messages/ConduitBundle$suffix.properties"))
        return stream.reader().use { reader -> Properties().apply { load(reader) }.stringPropertyNames() }
    }
}
