package dev.conduit.completion.engine

/**
 * Shapes raw model output into something worth showing: cut where the model should have
 * stopped, drop what the file already has, and never suggest nothing.
 */
object SuggestionShaper {
    /** Lines a multi-line suggestion may span. */
    const val MAX_LINES = 12

    /**
     * Decides how much of the text generated so far to keep, as tokens stream in.
     *
     * - Mid-line (text after the caret): a single line, cut at the first newline.
     * - At line end: up to [MAX_LINES], and stop at the first blank line once something was
     *   produced, which is where a block usually ends.
     *
     * @return the shaped text and whether generation can stop
     */
    fun shape(
        generated: String,
        context: CompletionContext,
    ): Shaped {
        if (!context.atLineEnd) {
            val newline = generated.indexOf('\n')
            return if (newline >= 0) Shaped(generated.substring(0, newline), done = true) else Shaped(generated, done = false)
        }
        val lines = generated.split('\n')
        val kept = ArrayList<String>()
        for ((index, line) in lines.withIndex()) {
            val last = index == lines.lastIndex
            if (line.isBlank() && kept.any { it.isNotBlank() } && !last) return Shaped(kept.joinToString("\n"), done = true)
            kept += line
            if (kept.size >= MAX_LINES) return Shaped(kept.joinToString("\n"), done = true)
        }
        return Shaped(kept.joinToString("\n"), done = false)
    }

    /**
     * Final clean-up once the stream ended: strip what repeats the text after the caret, drop
     * trailing blank lines, and reject suggestions that add nothing.
     */
    fun finish(
        text: String,
        context: CompletionContext,
    ): String? {
        var result = text.trimEnd('\n', ' ', '\t').let { if (context.atLineEnd) it else it.trimEnd() }
        val after = context.lineAfter.trimStart()
        // The model often re-types the closing bracket or the rest of the line that is already there.
        if (after.isNotEmpty() && result.endsWith(after)) result = result.removeSuffix(after).trimEnd()
        if (context.suffix.startsWith(result) && result.isNotEmpty()) return null
        return result.takeIf { it.isNotBlank() }
    }

    data class Shaped(
        val text: String,
        val done: Boolean,
    )
}
