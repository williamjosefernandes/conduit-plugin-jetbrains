package dev.conduit.orchestrator.chat

/** A piece of an assistant message: prose or a fenced code block. */
sealed interface MessageSegment {
    data class Prose(
        val markdown: String,
    ) : MessageSegment

    /**
     * @property language the fence's info string, e.g. `kotlin`; empty when absent
     * @property code the block's content without the fences
     * @property closed false while the block is still streaming
     */
    data class Code(
        val language: String,
        val code: String,
        val closed: Boolean,
    ) : MessageSegment
}

/** Splits a markdown message into prose and fenced code blocks, so code blocks get their own actions. */
object CodeBlocks {
    private val fence = Regex("""^(`{3,}|~{3,})(.*)$""")

    fun split(markdown: String): List<MessageSegment> {
        val segments = ArrayList<MessageSegment>()
        val prose = StringBuilder()
        val code = StringBuilder()
        var open: String? = null
        var language = ""

        fun flushProse() {
            if (prose.isNotBlank()) segments += MessageSegment.Prose(prose.toString().trim('\n'))
            prose.clear()
        }

        for (line in markdown.lines()) {
            val match = fence.find(line)
            when {
                open == null && match != null -> {
                    flushProse()
                    open = match.groupValues[1]
                    language = match.groupValues[2].trim().substringBefore(' ')
                }
                open != null && match != null && match.groupValues[1].startsWith(open) && match.groupValues[2].isBlank() -> {
                    segments += MessageSegment.Code(language, code.toString().removeSuffix("\n"), closed = true)
                    code.clear()
                    open = null
                }
                open != null -> code.append(line).append('\n')
                else -> prose.append(line).append('\n')
            }
        }
        if (open != null) {
            segments += MessageSegment.Code(language, code.toString().removeSuffix("\n"), closed = false)
        } else {
            flushProse()
        }
        return segments
    }
}
