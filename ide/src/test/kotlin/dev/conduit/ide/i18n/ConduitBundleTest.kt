package dev.conduit.ide.i18n

import dev.conduit.core.ActionKind
import dev.conduit.core.AgentMode
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.ErrorCode
import dev.conduit.core.PolicyDecision
import dev.conduit.core.StepStatus
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.LocalizedBundle
import dev.conduit.ide.actions.ActionScope
import dev.conduit.ide.actions.QuickAction
import dev.conduit.ide.context.ContextScope
import dev.conduit.ide.context.ContextSourceId
import dev.conduit.ide.i18n.ConduitLanguage.EN
import dev.conduit.ide.i18n.ConduitLanguage.PT_BR
import dev.conduit.ide.settings.LogLevel
import dev.conduit.mcp.ide.ChangeSetKind
import dev.conduit.orchestrator.review.Severity
import dev.conduit.orchestrator.task.TaskCategory
import dev.conduit.orchestrator.task.TaskColumn
import dev.conduit.orchestrator.task.TaskPhase
import dev.conduit.orchestrator.task.TaskStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

/** The translations themselves: both languages complete, consistent and well-formed, and the lookup rules. */
class ConduitBundleTest {
    private val english = properties("ConduitBundle.properties")
    private val portuguese = properties("ConduitBundle_pt_BR.properties")

    @Test
    fun `the chat placeholder is translated`() {
        assertEquals("Digite sua mensagem...", ConduitBundle.message(PT_BR, "chat.placeholder"))
        assertEquals("Type your message...", ConduitBundle.message(EN, "chat.placeholder"))
    }

    @Test
    fun `the tabs are named in each language`() {
        val names = { language: ConduitLanguage -> ConduitTab.entries.map { ConduitBundle.message(language, "tab.${it.name}") } }
        assertEquals(listOf("Chat", "Agente", "Revisão", "Tarefas", "Contexto", "Arquivos", "Ações"), names(PT_BR))
        assertEquals(listOf("Chat", "Agent", "Review", "Tasks", "Context", "Files", "Actions"), names(EN))
    }

    @Test
    fun `the settings pages are named in each language`() {
        val keys = listOf("general", "agents", "completion", "policies", "packs", "shortcuts", "privacy").map { "settings.$it" }
        assertEquals(
            listOf("Geral", "Agentes", "Autocompletar", "Políticas", "Packs", "Atalhos", "Privacidade"),
            keys.map { ConduitBundle.message(PT_BR, it) },
        )
        assertEquals(
            listOf("General", "Agents", "Autocomplete", "Policies", "Packs", "Shortcuts", "Privacy"),
            keys.map { ConduitBundle.message(EN, it) },
        )
    }

    @Test
    fun `parameters are filled in and technical values are kept as they are`() {
        assertEquals("Iniciando Claude Code…", ConduitBundle.message(PT_BR, "chat.starting", "Claude Code"))
        assertEquals("Starting Claude Code…", ConduitBundle.message(EN, "chat.starting", "Claude Code"))
        assertEquals(
            "Não foi possível aplicar as alterações: UserService.kt",
            ConduitBundle.message(PT_BR, "error.applyChanges", "UserService.kt"),
        )
    }

    @Test
    fun `plurals have a singular and a plural form`() {
        assertEquals("1 arquivo alterado", ConduitBundle.message(PT_BR, "task.verify.files", 1))
        assertEquals("4 arquivos alterados", ConduitBundle.message(PT_BR, "task.verify.files", 4))
        assertEquals("1 file changed", ConduitBundle.message(EN, "task.verify.files", 1))
        assertEquals("4 files changed", ConduitBundle.message(EN, "task.verify.files", 4))
        assertEquals("0 files", ConduitBundle.message(EN, "context.files.count", 0))
        assertEquals("1 alteração aguardando sua revisão", ConduitBundle.message(PT_BR, "chat.reviews.pending", 1))
        assertEquals("3 changes awaiting your review", ConduitBundle.message(EN, "chat.reviews.pending", 3))
        assertEquals("1 de 1 etapa", ConduitBundle.message(PT_BR, "agent.status.steps", 1, 1))
    }

    @Test
    fun `numbers follow the language and line numbers are not grouped`() {
        assertEquals("$1.50", ConduitBundle.message(EN, "format.usd", 1.5))
        assertEquals("US$ 1,50", ConduitBundle.message(PT_BR, "format.usd", 1.5))
        assertEquals("2 lines selected (lines 1200–1201)", ConduitBundle.message(EN, "context.selection.subtitle", 2, 1200, 1201))
    }

    @Test
    fun `a key missing from a translation falls back to English`() {
        val bundle = LocalizedBundle("messages.ConduitTestBundle", javaClass.classLoader)
        assertEquals("Olá, Ana!", bundle.message(PT_BR, "greeting", "Ana"))
        assertEquals("Only in English", bundle.message(PT_BR, "onlyInEnglish"))
        assertEquals("1 arquivo", bundle.message(PT_BR, "files", 1))
        assertEquals("Hello, Ana!", bundle.message(EN, "greeting", "Ana"))
    }

    @Test
    fun `English is used as English even on a Portuguese system`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        try {
            val bundle = LocalizedBundle("messages.ConduitTestBundle", javaClass.classLoader)
            assertEquals("Hello, Ana!", bundle.message(EN, "greeting", "Ana"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a key missing everywhere never breaks the interface nor shows the key`() {
        val text = ConduitBundle.message(PT_BR, "chat.send.button")
        assertEquals("Button", text)
        assertFalse(ConduitBundle.has("chat.send.button"))
    }

    @Test
    fun `both languages have the same keys`() {
        assertEquals("missing in pt_BR", emptySet<String>(), english.keys - portuguese.keys)
        assertEquals("only in pt_BR", emptySet<String>(), portuguese.keys - english.keys)
    }

    @Test
    fun `translations use the same placeholders as English`() {
        val placeholder = Regex("""\{(\d+)""")
        val mismatches =
            english.keys.filter { key ->
                placeholder.findAll(english.getValue(key)).map { it.groupValues[1] }.toSet() !=
                    placeholder.findAll(portuguese.getValue(key)).map { it.groupValues[1] }.toSet()
            }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun `every message with parameters is a valid pattern without stray quotes`() {
        val loneQuote = Regex("""(?<!')'(?!')""")
        val invalid =
            (english.map { (key, value) -> "en:$key" to value } + portuguese.map { (key, value) -> "pt_BR:$key" to value })
                .filter { (_, value) -> Regex("""\{\d""").containsMatchIn(value) }
                .filter { (_, value) -> loneQuote.containsMatchIn(value) || runCatching { MessageFormat(value) }.isFailure }
                .map { it.first }
        assertEquals(emptyList<String>(), invalid)
    }

    @Test
    fun `no message spells plurals as (s)`() {
        val spelled = (english + portuguese).filter { (_, value) -> value.contains("(s)") || value.contains("(ns)") }.keys
        assertEquals(emptySet<String>(), spelled)
    }

    @Test
    fun `every key the code asks for exists`() {
        val call = Regex("""message\(\s*(?:\w+,\s*)?"([a-zA-Z][\w.]*)"""")
        val missing =
            sources()
                .flatMap { file -> call.findAll(file.readText()).map { it.groupValues[1] to file.name } }
                .filter { (key, _) -> key !in english }
                .toList()
        assertEquals(emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun `every key built from an enum exists`() {
        val families =
            listOf(
                "tab." to ConduitTab.entries,
                "error." to ErrorCode.entries,
                "changeSet." to ChangeSetKind.entries,
                "mode." to AgentMode.entries,
                "autonomy." to AutonomyLevel.entries,
                "decision." to PolicyDecision.entries,
                "action.kind." to ActionKind.entries,
                "settings.policy.kind." to ActionKind.entries,
                "severity." to Severity.entries,
                "phase." to TaskPhase.entries,
                "stage." to TaskStage.entries,
                "column." to TaskColumn.entries,
                "category." to TaskCategory.entries,
                "step." to StepStatus.entries,
                "logLevel." to LogLevel.entries,
                "context.source." to ContextSourceId.entries,
                "chat.scope." to ContextScope.entries,
                "scope." to ActionScope.entries,
                "action.quick." to QuickAction.entries,
            )
        val missing = families.flatMap { (prefix, values) -> values.map { "$prefix${it.name}" } }.filter { it !in english }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every action in plugin xml has its text in both languages`() {
        val pluginXml = File(root(), "plugin/src/main/resources/META-INF/plugin.xml").readText()
        val ids =
            Regex("""<action id="([^"]+)" class=""")
                .findAll(pluginXml)
                .map { it.groupValues[1] }
                .toList()
        assertTrue(ids.isNotEmpty())
        val missing = ids.map { "action.$it.text" }.filter { it !in english || it !in portuguese }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `Portuguese uses alteracoes, executar and the IDE consistently`() {
        val wrong = Regex("""\bmudanças?\b|\b[Rr]od(ar|a|e)\b|\bdo IDE\b""")
        assertEquals(emptyList<String>(), portuguese.filter { (_, value) -> wrong.containsMatchIn(value) }.keys.toList())
    }

    @Test
    fun `the language selector lists the languages by their own names`() {
        assertEquals(listOf("Português (Brasil)", "English"), ConduitLanguage.entries.map { it.displayName })
        assertNotEquals(ConduitBundle.message(PT_BR, "settings.language"), ConduitBundle.message(EN, "settings.language"))
    }

    private fun properties(name: String): Map<String, String> {
        val properties = Properties()
        javaClass.classLoader
            .getResourceAsStream("messages/$name")!!
            .reader(Charsets.UTF_8)
            .use(properties::load)
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    /** The repository root: tests run in the module's directory. */
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources(): Sequence<File> =
        root()
            .listFiles()
            .orEmpty()
            .asSequence()
            .map { File(it, "src/main/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
}
