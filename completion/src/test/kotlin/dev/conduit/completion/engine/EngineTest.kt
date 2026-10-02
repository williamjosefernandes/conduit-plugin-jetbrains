package dev.conduit.completion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the completion engine; no IDE needed. */
class EngineTest {
    private val text = "line one\nline two\nline three\nline four\n"

    @Test
    fun contextCutsAtLineBoundariesWithinTheBudget() {
        val caret = text.indexOf("three")
        val context = CompletionContext.of(text, caret, prefixChars = 16, suffixChars = 100)

        assertEquals("line two\nline ", context.prefix)
        assertEquals("three\nline four\n", context.suffix)
        assertEquals("line ", context.lineBefore)
        assertEquals("three", context.lineAfter)
        assertFalse(context.atLineEnd)

        val end = CompletionContext.of(text, text.indexOf("\nline three"), prefixChars = 1_000, suffixChars = 12)
        assertTrue(end.atLineEnd)
        assertEquals("\nline three", end.suffix)
        assertEquals("line two", end.lineBefore)
    }

    @Test
    fun headerCountsAgainstThePrefixBudget() {
        val context = CompletionContext.of(text, text.length, header = "// related\n", prefixChars = 22)
        assertTrue(context.prefix.startsWith("// related\n"))
        assertEquals("// related\nline four\n", context.prefix)
    }

    @Test
    fun midLineSuggestionsStopAtTheFirstNewline() {
        val context = CompletionContext("fun a(", ")", "fun a(", ")")
        assertEquals(SuggestionShaper.Shaped("x: Int", done = true), SuggestionShaper.shape("x: Int\n) {", context))
        assertEquals(SuggestionShaper.Shaped("x: In", done = false), SuggestionShaper.shape("x: In", context))
    }

    @Test
    fun lineEndSuggestionsStopAtABlankLineOrTheLineCap() {
        val context = CompletionContext("fun a() {\n", "", "", "")
        assertEquals(SuggestionShaper.Shaped("    return 1\n}", done = true), SuggestionShaper.shape("    return 1\n}\n\nfun b()", context))
        val long = (1..20).joinToString("\n") { "x$it" }
        val shaped = SuggestionShaper.shape(long, context)
        assertTrue(shaped.done)
        assertEquals(SuggestionShaper.MAX_LINES, shaped.text.lines().size)
    }

    @Test
    fun finishDropsWhatTheFileAlreadyHas() {
        val context = CompletionContext("foo(", ")\nbar()", "foo(", ")")
        assertEquals("1, 2", SuggestionShaper.finish("1, 2)", context))
        assertNull(SuggestionShaper.finish(")", context))
        assertNull(SuggestionShaper.finish("   \n", context))
        assertEquals("x", SuggestionShaper.finish("x\n\n", CompletionContext("", "", "", "")))
    }

    @Test
    fun cacheReusesSuggestionsAsTheUserTypesThem() {
        val cache = CompletionCache()
        cache.put("A.kt", "val x = ", "\n", "listOf(1, 2)")

        assertEquals("listOf(1, 2)", cache.get("A.kt", "val x = ", "\n"))
        assertEquals("tOf(1, 2)", cache.get("A.kt", "val x = lis", "\n"))
        assertNull(cache.get("A.kt", "val x = lisx", "\n"))
        assertNull(cache.get("A.kt", "val x = listOf(1, 2)", "\n"))
        assertNull(cache.get("B.kt", "val x = ", "\n"))
        assertNull(cache.get("A.kt", "val x = ", "\n// other suffix"))
    }

    @Test
    fun latencyStatsDriveTheDebounce() {
        val stats = LatencyStats(window = 5)
        assertEquals(LatencyStats.DEFAULT_DEBOUNCE_MS, stats.debounceMillis())
        listOf(100L, 200L, 300L, 400L, 500L, 900L).forEach(stats::record)
        assertEquals(5, stats.count())
        assertEquals(400L, stats.p50)
        assertEquals(900L, stats.p95)
        assertEquals(133L, stats.debounceMillis())
        repeat(5) { stats.record(30) }
        assertEquals(60L, stats.debounceMillis())
    }
}
