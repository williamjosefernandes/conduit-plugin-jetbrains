package dev.conduit.completion.engine

/**
 * The text window sent to the model: as much as the budget allows around the caret, cut at
 * line boundaries so the model never sees half a token of context.
 *
 * @property prefix text before the caret, after budgeting
 * @property suffix text after the caret, after budgeting
 * @property lineBefore the caret's line up to the caret
 * @property lineAfter the caret's line from the caret
 */
data class CompletionContext(
    val prefix: String,
    val suffix: String,
    val lineBefore: String,
    val lineAfter: String,
) {
    /** True when only whitespace follows the caret on its line: a multi-line completion makes sense. */
    val atLineEnd: Boolean get() = lineAfter.isBlank()

    companion object {
        /** Characters, not tokens: cheap to compute and close enough for a budget. */
        const val DEFAULT_PREFIX_CHARS = 6_000
        const val DEFAULT_SUFFIX_CHARS = 2_000

        /**
         * Windows [text] around [offset]. A [header] (related code from other files, as a comment)
         * is placed before the prefix and counted against its budget.
         */
        fun of(
            text: CharSequence,
            offset: Int,
            header: String = "",
            prefixChars: Int = DEFAULT_PREFIX_CHARS,
            suffixChars: Int = DEFAULT_SUFFIX_CHARS,
        ): CompletionContext {
            val caret = offset.coerceIn(0, text.length)
            val before = text.subSequence(0, caret)
            val after = text.subSequence(caret, text.length)
            val lineStart = before.lastIndexOf('\n') + 1
            val lineEnd = after.indexOf('\n').let { if (it < 0) after.length else it }
            // The caret's own line is always kept whole, whatever the budget.
            val budget = (prefixChars - header.length).coerceAtLeast(0)
            var prefixFrom = (before.length - budget).coerceAtLeast(0)
            if (prefixFrom > 0) {
                val newline = before.indexOf('\n', prefixFrom)
                prefixFrom = if (newline in 0 until lineStart) newline + 1 else lineStart
            }
            var suffixTo = after.length.coerceAtMost(suffixChars)
            if (suffixTo < after.length) {
                val newline = after.lastIndexOf('\n', suffixTo)
                if (newline > 0) suffixTo = newline
            }
            return CompletionContext(
                prefix = header + before.subSequence(prefixFrom, before.length),
                suffix = after.subSequence(0, suffixTo).toString(),
                lineBefore = before.subSequence(lineStart, before.length).toString(),
                lineAfter = after.subSequence(0, lineEnd).toString(),
            )
        }
    }
}
