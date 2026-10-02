package dev.conduit.orchestrator.context

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Token arithmetic for the context budget. Agents use different tokenizers and do not expose
 * them, so counts are estimates: about four characters per token for code and prose, which is
 * what the major tokenizers average on source code.
 */
object Tokens {
    private const val CHARS_PER_TOKEN = 4.0
    private const val THOUSAND = 1_000
    private const val MILLION = 1_000_000

    fun estimate(text: String): Int = if (text.isEmpty()) 0 else ceil(text.length / CHARS_PER_TOKEN).toInt()

    /** `270`, `1.2k`, `200k`, `1.5M`. */
    fun format(tokens: Int): String =
        when {
            tokens < THOUSAND -> tokens.toString()
            tokens < MILLION -> compact(tokens.toDouble() / THOUSAND) + "k"
            else -> compact(tokens.toDouble() / MILLION) + "M"
        }

    /** Share of [budget] that [used] takes, as a whole percentage; never 0 when something is used. */
    fun percent(
        used: Int,
        budget: Int,
    ): Int {
        if (budget <= 0) return 0
        val share = used * PERCENT / budget
        return if (used > 0 && share < 1.0) 1 else share.roundToInt()
    }

    private const val PERCENT = 100.0

    /** From this value on, decimals are not shown: `120k`, not `120.4k`. */
    private const val NO_DECIMALS_FROM = 100

    private fun compact(value: Double): String =
        if (value >= NO_DECIMALS_FROM || value == value.toLong().toDouble()) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
        }
}

/**
 * Hides secrets in text before it is sent to an agent: private keys, well-known token formats,
 * and the values of assignments whose name says they are secret.
 */
object SecretMasker {
    const val MASK = "‹redacted›"

    private val patterns =
        listOf(
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            // AWS access keys, GitHub, Slack, Stripe, OpenAI/Anthropic and Google API keys, JWTs.
            Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Regex("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"),
            Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"),
            Regex("\\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{16,}\\b"),
            Regex("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}\\b"),
            Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        )

    private const val NAME =
        "(?:[A-Za-z0-9_.-]*?)(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credentials?)[A-Za-z0-9_.-]*"

    /** A secret-looking name assigned a quoted literal in code: `password = "…"`, `"clientSecret": "…"`. */
    private val quoted = Regex("""(?i)($NAME["']?\s*[:=]\s*)(["'])([^"'\n]{4,})\2""")

    /** An unquoted value in a properties, env or YAML line: `API_KEY=…`, `password: …`. */
    private val bare = Regex("""(?im)^(\s*$NAME\s*[:=]\s*)([^\s#"'(){}]{4,})\s*$""")

    /** Returns [text] with secrets replaced by [MASK], and how many were replaced. */
    fun mask(text: String): Pair<String, Int> {
        var count = 0
        var result = text
        patterns.forEach { pattern ->
            result =
                pattern.replace(result) {
                    count++
                    MASK
                }
        }
        result =
            quoted.replace(result) { match ->
                val (name, quote, value) = match.groupValues.drop(1)
                if (value == MASK || value.startsWith("\${")) {
                    match.value
                } else {
                    count++
                    name + quote + MASK + quote
                }
            }
        result =
            bare.replace(result) { match ->
                val value = match.groupValues[2]
                if (value == MASK || isReference(value)) {
                    match.value
                } else {
                    count++
                    match.groupValues[1] + MASK
                }
            }
        return result to count
    }

    /** `${VAR}`, `$VAR` or `some.property`: a pointer to a secret, not the secret itself. */
    private fun isReference(value: String): Boolean = value.startsWith("$") || (value.contains('.') && !value.contains('/'))
}
