package dev.conduit.ide.i18n

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.conduit.core.ErrorCode
import dev.conduit.core.Failure
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.i18n.ConduitLanguage.EN
import dev.conduit.ide.i18n.ConduitLanguage.PT_BR
import dev.conduit.ide.review.ReviewService
import dev.conduit.ide.settings.ConduitSettings
import java.util.Locale

/** Choosing, keeping and switching the language of the interface. */
class ConduitLanguageServiceTest : BasePlatformTestCase() {
    private val settings get() = ConduitSettings.getInstance()
    private val service get() = ConduitLanguageService.getInstance()
    private var stored: String? = null

    override fun setUp() {
        super.setUp()
        stored = settings.state.language
    }

    override fun tearDown() {
        try {
            settings.state.language = stored
            service.reload()
        } finally {
            super.tearDown()
        }
    }

    fun testTextFollowsTheSelectedLanguageImmediately() {
        service.select(PT_BR)
        assertEquals("Digite sua mensagem...", ConduitBundle.message("chat.placeholder"))
        assertEquals("Revisão", ConduitBundle.message("tab.REVIEW"))

        service.select(EN)
        assertEquals("Type your message...", ConduitBundle.message("chat.placeholder"))
        assertEquals("Review", ConduitBundle.message("tab.REVIEW"))
    }

    fun testTheCodeIsStoredNotTheName() {
        service.select(PT_BR)
        assertEquals("pt_BR", settings.state.language)
        service.select(EN)
        assertEquals("en", settings.state.language)
    }

    fun testPortugueseSurvivesARestart() {
        assertSurvivesRestart(PT_BR)
    }

    fun testEnglishSurvivesARestart() {
        assertSurvivesRestart(EN)
    }

    /** Selects [language], writes the settings as the IDE does, and reads them back into new components. */
    private fun assertSurvivesRestart(language: ConduitLanguage) {
        val other = ConduitLanguage.entries.first { it != language }
        service.select(other)
        service.select(language)
        val written = XmlSerializer.serialize(settings.state)

        // A fresh start: the settings component is created again from conduit.xml.
        val restarted = ConduitSettings()
        restarted.loadState(XmlSerializer.deserialize(written, ConduitSettings.State::class.java))
        assertEquals(language, ConduitLanguage.resolve(restarted.state.language) { other })

        // The running IDE receives the same file (Settings Sync, a reload from disk): the interface follows it.
        settings.state.language = other.code
        service.reload()
        settings.loadState(XmlSerializer.deserialize(written, ConduitSettings.State::class.java))
        assertEquals(language, service.language.value)
        assertEquals(language, ConduitLanguageService.current())
    }

    fun testWithoutAChoiceTheLanguageIsDetected() {
        settings.state.language = null
        service.reload()
        assertFalse(service.isChosen)
        assertEquals(ConduitLanguage.detect(), service.language.value)

        service.select(PT_BR)
        assertTrue(service.isChosen)
    }

    fun testDetectionPrefersPortugueseFromTheIdeOrTheSystem() {
        val portuguese = Locale.forLanguageTag("pt-BR")
        assertEquals(PT_BR, ConduitLanguage.detect(ide = portuguese, system = Locale.US))
        assertEquals(PT_BR, ConduitLanguage.detect(ide = Locale.ENGLISH, system = portuguese))
        assertEquals(PT_BR, ConduitLanguage.detect(ide = Locale.forLanguageTag("pt-PT"), system = Locale.US))
        assertEquals(EN, ConduitLanguage.detect(ide = Locale.ENGLISH, system = Locale.US))
        assertEquals(EN, ConduitLanguage.detect(ide = Locale.JAPANESE, system = portuguese))
        assertEquals(EN, ConduitLanguage.detect(ide = Locale.ENGLISH, system = Locale.GERMANY))
    }

    fun testStoredCodesAreRead() {
        assertEquals(PT_BR, ConduitLanguage.fromCode("pt_BR"))
        assertEquals(PT_BR, ConduitLanguage.fromCode("pt-BR"))
        assertEquals(EN, ConduitLanguage.fromCode("en"))
        assertNull(ConduitLanguage.fromCode("es"))
        assertNull(ConduitLanguage.fromCode(""))
        assertNull(ConduitLanguage.fromCode(null))
    }

    fun testListenersAreToldOnceOnlyWhenTheLanguageChanges() {
        service.select(EN)
        val heard = mutableListOf<ConduitLanguage>()
        val connection = application.messageBus.connect(testRootDisposable)
        connection.subscribe(ConduitLanguageListener.TOPIC, ConduitLanguageListener { heard += it })

        service.select(PT_BR)
        service.select(PT_BR)
        service.select(EN)

        assertEquals(listOf(PT_BR, EN), heard)
    }

    fun testChangingTheLanguageTouchesNoOtherSetting() {
        val state = settings.state
        state.defaultAgentId = "opencode"
        state.models = mutableMapOf("claude-code" to "sonnet")
        try {
            val before =
                XmlSerializer.serialize(state).also {
                    it.getChildren("option").removeIf { o ->
                        o.getAttributeValue("name") ==
                            "language"
                    }
                }
            service.select(PT_BR)
            service.select(EN)
            val after =
                XmlSerializer.serialize(state).also {
                    it.getChildren("option").removeIf { o ->
                        o.getAttributeValue("name") ==
                            "language"
                    }
                }
            assertEquals(
                com.intellij.openapi.util.JDOMUtil
                    .write(before),
                com.intellij.openapi.util.JDOMUtil
                    .write(after),
            )
        } finally {
            state.defaultAgentId = ConduitSettings.DEFAULT_AGENT_ID
            state.models = mutableMapOf()
        }
    }

    fun testActionTextsFollowTheLanguage() {
        val action =
            object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) = Unit
            }
        action.useConduitText("dev.conduit.openChat")

        service.select(PT_BR)
        assertEquals("Chat do Conduit", action.templatePresentation.text)
        service.select(EN)
        assertEquals("Conduit Chat", action.templatePresentation.text)
    }

    fun testErrorsAreToldInTheUsersLanguageWithTechnicalValuesKept() {
        val failure = Failure(ErrorCode.AGENT_AUTH_REQUIRED, listOf("Claude Code", "claude /login"))
        service.select(PT_BR)
        assertEquals(
            "Autenticação necessária para Claude Code. Execute claude /login em um terminal para fazer login.",
            ConduitErrors.describe(failure),
        )
        assertEquals("Não foi possível iniciar o agente.", ConduitErrors.describe(Failure(ErrorCode.AGENT_START_FAILED)))
        assertEquals(
            "Bloqueado nas configurações do Conduit: Rede.",
            ConduitErrors.describe(Failure(ErrorCode.POLICY_KIND_BLOCKED, listOf("NETWORK"))),
        )
        service.select(EN)
        assertEquals("Authentication required for Claude Code. Run claude /login in a terminal to log in.", ConduitErrors.describe(failure))
        assertEquals("Agent not found: kiro.", ConduitErrors.describe(Failure(ErrorCode.AGENT_NOT_FOUND, listOf("kiro"))))
    }

    fun testUnknownErrorsNeverShowAStackTrace() {
        service.select(EN)
        val error = IllegalStateException("boom\n\tat dev.conduit.Some.method(Some.kt:1)")
        assertEquals("Something went wrong: boom", ConduitErrors.describe(error))
        assertEquals("Something went wrong. The details are in the IDE log.", ConduitErrors.describe(RuntimeException()))
    }

    fun testTextKeptForLaterFollowsTheLanguage() {
        val text = UserText.message("review.error.nothing")
        service.select(PT_BR)
        assertEquals("Não há nada para revisar: nenhuma alteração.", text.text())
        service.select(EN)
        assertEquals("There is nothing to review: no changes.", text.text())
    }

    fun testTheDefaultChecklistFollowsTheLanguage() {
        settings.state.reviewChecklist = ""
        service.select(PT_BR)
        assertEquals("Cobertura de testes", settings.checklist().last())
        service.select(EN)
        assertEquals("Test coverage", settings.checklist().last())
        assertTrue(ConduitSettings.isDefaultChecklist(ConduitSettings.defaultChecklist(PT_BR).joinToString("\n")))
        assertFalse(ConduitSettings.isDefaultChecklist("Security"))
    }

    fun testAnExportedReviewHasHeadingsInTheLanguage() {
        service.select(PT_BR)
        val labels = ReviewService.markdownLabels()
        assertEquals("Revisão de código", labels.title)
        assertEquals("alta", labels.severity(dev.conduit.orchestrator.review.Severity.HIGH))
    }

    fun testRepliesToConduitsOwnPromptsAreAskedInTheLanguage() {
        service.select(PT_BR)
        assertEquals(
            "Brazilian Portuguese",
            dev.conduit.ide.tasks.TaskService
                .replyLanguage(),
        )
        service.select(EN)
        assertEquals(
            "English",
            dev.conduit.ide.tasks.TaskService
                .replyLanguage(),
        )
    }

    private val application get() =
        com.intellij.openapi.application.ApplicationManager
            .getApplication()
}
