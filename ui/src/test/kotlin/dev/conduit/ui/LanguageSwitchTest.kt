package dev.conduit.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.i18n.ConduitLanguage
import dev.conduit.ide.i18n.ConduitLanguageService
import dev.conduit.ide.settings.ConduitSettings

/** A change of language reaches what is already on screen, without rebuilding it. */
class LanguageSwitchTest : BasePlatformTestCase() {
    private var stored: String? = null

    override fun setUp() {
        super.setUp()
        stored = ConduitSettings.getInstance().state.language
        LanguageRefresh.install()
    }

    override fun tearDown() {
        try {
            ConduitSettings.getInstance().state.language = stored
            ConduitLanguageService.getInstance().reload()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    fun testOpenScreensRedrawInTheNewLanguageAndKeepTheirState() {
        val languages = ConduitLanguageService.getInstance()
        select(ConduitLanguage.PT_BR)
        val drawn = mutableListOf<String>()
        var clicks: MutableState<Int>? = null
        val scene = ImageComposeScene(width = 200, height = 100, density = Density(1f))
        try {
            scene.setContent {
                val count = remember { mutableStateOf(0) }.also { clicks = it }
                val label = ConduitBundle.message("tab.REVIEW")
                val shown = "$label/${count.value}"
                SideEffect { drawn += shown }
            }
            redraw(scene)
            clicks!!.value = 3
            redraw(scene)
            assertEquals("Revisão/3", drawn.last())

            select(ConduitLanguage.EN)
            redraw(scene)
            assertEquals("the text changed and the state typed in stayed", "Review/3", drawn.last())
            assertEquals(ConduitLanguage.EN, languages.language.value)
        } finally {
            scene.close()
        }
    }

    private fun select(language: ConduitLanguage) {
        ConduitLanguageService.getInstance().select(language)
        // The UI hears of the change on the EDT.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private var frame = 0L

    /** Draws the next frame: an off-screen scene recomposes only when its clock moves. */
    private fun redraw(scene: ImageComposeScene) {
        Snapshot.sendApplyNotifications()
        frame += FRAME_NANOS
        scene.render(frame)
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}
