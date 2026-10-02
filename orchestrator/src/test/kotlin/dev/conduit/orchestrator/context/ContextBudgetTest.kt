package dev.conduit.orchestrator.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextBudgetTest {
    @Test
    fun `tokens are estimated and formatted`() {
        assertEquals(0, Tokens.estimate(""))
        assertEquals(3, Tokens.estimate("123456789"))
        assertEquals("270", Tokens.format(270))
        assertEquals("1.2k", Tokens.format(1_200))
        assertEquals("200k", Tokens.format(200_000))
        assertEquals("1.5M", Tokens.format(1_500_000))
        assertEquals(2, Tokens.percent(4_800, 200_000))
        assertEquals(1, Tokens.percent(10, 200_000))
        assertEquals(0, Tokens.percent(0, 200_000))
    }

    @Test
    fun `secrets are masked`() {
        val text =
            """
            API_KEY=abcd1234efgh
            val password = "hunter2hunter"
            token: ${'$'}{SECRET_FROM_ENV}
            key = "AKIAABCDEFGHIJKLMNOP"
            -----BEGIN RSA PRIVATE KEY-----
            abc
            -----END RSA PRIVATE KEY-----
            """.trimIndent()
        val (masked, count) = SecretMasker.mask(text)
        assertFalse("abcd1234efgh" in masked)
        assertFalse("hunter2hunter" in masked)
        assertFalse("AKIAABCDEFGHIJKLMNOP" in masked)
        assertFalse("abc\n-----END" in masked)
        assertTrue("\${SECRET_FROM_ENV}" in masked)
        assertEquals(4, count)
    }

    @Test
    fun `code that merely mentions secrets is left alone`() {
        val code = "val token = request.token\nfun password(): String = repository.passwordOf(user)"
        assertEquals(code, SecretMasker.mask(code).first)
    }
}
