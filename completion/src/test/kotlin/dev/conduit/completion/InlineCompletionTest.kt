@file:Suppress("DEPRECATION")

package dev.conduit.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.sun.net.httpserver.HttpServer
import dev.conduit.completion.engine.CompletionContext
import dev.conduit.llm.FastProvider
import dev.conduit.llm.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The completion path inside the IDE against a fake Ollama: context from a real editor, the
 * service's shaping, caching and latency bookkeeping, and the syntax validation on real PSI.
 */
class InlineCompletionTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private val answer = AtomicReference("a + b")
    private val prompts = CopyOnWriteArrayList<String>()

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/generate") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
            prompts += body["prompt"]!!.jsonPrimitive.content
            val text = Json.encodeToString(kotlinx.serialization.serializer<String>(), answer.get())
            val bytes = "{\"response\":$text,\"done\":false}\n{\"response\":\"\",\"done\":true}\n".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val settings = CompletionSettings.getInstance().state
        settings.models =
            mutableListOf(
                FastModelState().apply {
                    id = "fake"
                    provider = FastProvider.OLLAMA
                    model = "qwen2.5-coder:1.5b"
                    baseUrl = "http://127.0.0.1:${server.address.port}"
                },
            )
        settings.selectedModelId = "fake"
        CompletionService.getInstance().secrets = SecretStore { null }
        CompletionService.getInstance().reset()
    }

    override fun tearDown() {
        try {
            server.stop(0)
            CompletionSettings.getInstance().state.models = mutableListOf()
            CompletionSettings.getInstance().state.selectedModelId = ""
            CompletionService.getInstance().reset()
        } finally {
            super.tearDown()
        }
    }

    private fun complete(context: CompletionContext): String? =
        runBlocking {
            withTimeout(TIMEOUT_MS) { CompletionService.getInstance().complete("A.java", "java", context) }
        }

    fun testTheModelIsAskedWithTheEditorContextAndTheAnswerIsCached() {
        val context = CompletionContext.of("class A {\n    int add(int a, int b) { return ; }\n}\n", 45)
        assertEquals("    int add(int a, int b) { return ", context.lineBefore)

        assertEquals("a + b", complete(context))
        assertTrue(prompts.single().endsWith("return "))
        assertTrue(CompletionService.getInstance().stats.count() >= 1)

        assertEquals("a + b", complete(context))
        assertEquals("the second request is served from the cache", 1, prompts.size)
    }

    fun testMidLineSuggestionsAreCutToOneLineAndDoNotRepeatTheSuffix() {
        answer.set("a + b; }\n    int other() {")
        val context = CompletionContext.of("class A {\n    int add(int a, int b) { return ; }\n}\n", 45)
        assertEquals("a + b", complete(context))
    }

    fun testAnUnreachableModelYieldsNothingAndIsRemembered() {
        server.stop(0)
        val context = CompletionContext.of("x = ", 4)
        assertNull(complete(context))
        assertFalse(
            CompletionService
                .getInstance()
                .health.value
                .getValue("fake")
                .reachable,
        )
    }

    fun testSuggestionsThatBreakTheSyntaxAreRejected() {
        val file = myFixture.addFileToProject("B.java", "class B {\n    int one() { return 1; }\n}\n")
        val request =
            runInEdtAndGet {
                myFixture.openFileInEditor(file.virtualFile)
                val offset = file.text.indexOf("return 1;") + "return 1;".length
                myFixture.editor.caretModel.moveToOffset(offset)
                InlineCompletionRequest(
                    InlineCompletionEvent.DirectCall(myFixture.editor, myFixture.editor.caretModel.currentCaret, null),
                    file,
                    myFixture.editor,
                    myFixture.editor.document,
                    offset,
                    offset,
                )
            }
        runBlocking {
            assertTrue(PsiValidator.accepts(request, " int two = 2;"))
            assertFalse(PsiValidator.accepts(request, " int two = ((;"))
        }
    }

    fun testTheProviderIsOnlyEnabledWhenTheSettingIsOn() {
        val provider = ConduitInlineCompletionProvider()
        val file = myFixture.addFileToProject("C.java", "class C {}\n")
        val event =
            runInEdtAndGet {
                myFixture.openFileInEditor(file.virtualFile)
                InlineCompletionEvent.DirectCall(myFixture.editor, myFixture.editor.caretModel.currentCaret, null)
            }
        assertTrue(provider.isEnabled(event))
        CompletionSettings.getInstance().state.enabled = false
        try {
            assertFalse(provider.isEnabled(event))
        } finally {
            CompletionSettings.getInstance().state.enabled = true
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
